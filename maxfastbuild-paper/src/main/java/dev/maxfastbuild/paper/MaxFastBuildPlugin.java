package dev.maxfastbuild.paper;

import com.google.gson.Gson;
import dev.maxfastbuild.api.*;
import dev.maxfastbuild.core.billing.BillingPolicy;
import dev.maxfastbuild.core.limit.TokenBucket;
import dev.maxfastbuild.core.protocol.*;
import dev.maxfastbuild.core.shape.DefaultShapeGenerator;
import dev.maxfastbuild.core.shape.ShapeLimitException;
import dev.maxfastbuild.core.task.*;
import dev.maxfastbuild.storage.*;
import net.milkbowl.vault.economy.Economy;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MaxFastBuildPlugin extends JavaPlugin implements Listener {
    private static final Gson GSON = new Gson();
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, TokenBucket> limits = new ConcurrentHashMap<>();
    private final Map<UUID, SecureProtocol.Session> sessions = new ConcurrentHashMap<>();
    private final CommandChunkAssembler chunks = new CommandChunkAssembler(Clock.systemUTC(), Duration.ofSeconds(15));
    private SecureProtocol protocol;
    private SqliteDatabase database;
    private SqliteTaskRepository tasks;
    private EconomyLedger ledger;
    private TaskExecutor executor;
    private EconomyService economy;
    private AuditService audit;
    private PluginMessages messages;
    /** Guards handlers and ticks during PlugMan unload / failed enable. */
    private volatile boolean active;

    PluginMessages messages() {
        return messages;
    }

    @Override public void onEnable() {
        active = false;
        saveDefaultConfig();
        messages = new PluginMessages(this);
        messages.reload();
        try {
            protocol = new SecureProtocol(Clock.systemUTC(), Duration.ofMinutes(getConfig().getLong("protocol.session-minutes", 30)), getConfig().getInt("protocol.max-payload-bytes", 16384));
            database = new SqliteDatabase(getDataFolder().toPath().resolve("maxfastbuild.db"));
            tasks = new SqliteTaskRepository(database);
            ledger = new EconomyLedger(database);
            tasks.initialize();
            ledger.initialize();
            audit = safeDiscoverAudit();
            economy = safeDiscoverEconomy();
            executor = new TaskExecutor(tasks, new PaperWorldAccess(), audit, Clock.systemUTC());
            validateIntegrations();

            PublicCommand publicCommand = new PublicCommand(this);
            PluginCommand mfb = Objects.requireNonNull(getCommand("mfb"), "mfb command missing from plugin.yml");
            mfb.setExecutor(publicCommand);
            mfb.setTabCompleter(publicCommand);
            PluginCommand mfbAdmin = Objects.requireNonNull(getCommand("mfbadmin"), "mfbadmin command missing from plugin.yml");
            mfbAdmin.setExecutor((sender, command, label, args) -> handleAdmin(sender, args));
            getServer().getPluginManager().registerEvents(this, this);
            resumeTasks();
            long period = Math.max(1, getConfig().getLong("execution.ticks-per-block", 1));
            getServer().getScheduler().runTaskTimer(this, this::tickTasks, period, period);
            active = true;
            getLogger().info("CLI messages language: " + messages.language());
            getLogger().info("CoreProtect mode: vanilla breakNaturally + one API logPlacement; no synthetic break events / no double logRemoval");
            ensureSqliteDriver();
        } catch (RuntimeException ex) {
            getLogger().severe("MaxFastBuild failed to enable: " + ex.getMessage());
            shutdownResources(false);
            throw ex;
        }
    }

    /** SQLite is not shaded; require a driver on the classpath (Paper libraries or server). */
    private void ensureSqliteDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            getLogger().severe(
                    "org.sqlite.JDBC not found. Place sqlite-jdbc on the server classpath "
                            + "(e.g. paper libraries) or install a JDBC provider. MaxFastBuild no longer bundles SQLite.");
        }
    }

    @Override public void onDisable() {
        // Order matters for PlugMan unload: stop ticks first, then persist, then close DB.
        active = false;
        try {
            getServer().getScheduler().cancelTasks(this);
        } catch (RuntimeException ex) {
            getLogger().warning("Failed to cancel scheduler tasks: " + ex.getMessage());
        }
        try {
            HandlerList.unregisterAll((Listener) this);
        } catch (RuntimeException ex) {
            getLogger().warning("Failed to unregister listeners: " + ex.getMessage());
        }
        clearCommandHandlers();
        shutdownResources(true);
    }

    /**
     * Persist active work, clear in-memory state, close SQLite.
     * @param pauseTasks when true (normal disable), mark QUEUED/RUNNING as PAUSED_SHUTDOWN for reload resume.
     */
    private void shutdownResources(boolean pauseTasks) {
        try {
            if (pauseTasks && tasks != null && executor != null) {
                Instant now = Instant.now();
                // Prefer executor memory (fresh applied_count), then any remaining DB-active rows.
                for (UUID id : executor.activeIds()) {
                    try {
                        BuildTask latest = tasks.find(id).orElse(null);
                        executor.detach(id);
                        if (latest == null) continue;
                        latest = tasks.find(id).orElse(latest);
                        if (latest.status() == TaskStatus.RUNNING || latest.status() == TaskStatus.QUEUED) {
                            tasks.save(latest.transition(TaskStatus.PAUSED_SHUTDOWN, now));
                        }
                    } catch (RuntimeException ex) {
                        getLogger().warning("Failed to pause task " + id + " on disable: " + ex.getMessage());
                    }
                }
                try {
                    for (BuildTask task : tasks.recoverable()) {
                        if (task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.QUEUED) continue;
                        if (executor != null) executor.detach(task.id());
                        BuildTask latest = tasks.find(task.id()).orElse(task);
                        if (latest.status() == TaskStatus.RUNNING || latest.status() == TaskStatus.QUEUED) {
                            tasks.save(latest.transition(TaskStatus.PAUSED_SHUTDOWN, now));
                        }
                    }
                } catch (RuntimeException ex) {
                    getLogger().warning("Failed to scan recoverable tasks on disable: " + ex.getMessage());
                }
            }
        } finally {
            if (executor != null) {
                try { executor.clear(); } catch (RuntimeException ignored) { }
            }
            selections.clear();
            limits.clear();
            sessions.clear();
            try { chunks.clear(); } catch (RuntimeException ignored) { }
            if (tasks != null) {
                try { tasks.closeQuietly(); } catch (RuntimeException ex) {
                    getLogger().warning("SQLite close failed: " + ex.getMessage());
                }
            }
            tasks = null;
            ledger = null;
            database = null;
            executor = null;
            economy = null;
            audit = null;
            protocol = null;
            // keep messages instance for late admin feedback if needed; null on hard fail
        }
    }

    private void clearCommandHandlers() {
        try {
            PluginCommand mfb = getCommand("mfb");
            if (mfb != null) {
                mfb.setExecutor(null);
                mfb.setTabCompleter(null);
            }
            PluginCommand mfbAdmin = getCommand("mfbadmin");
            if (mfbAdmin != null) mfbAdmin.setExecutor(null);
        } catch (RuntimeException ex) {
            getLogger().warning("Failed to clear command handlers: " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInternalCommand(PlayerCommandPreprocessEvent event) {
        if (!active || tasks == null || executor == null) return;
        String message = event.getMessage();
        if (message.startsWith("/")) message = message.substring(1);
        if (!message.equals("__mfb") && !message.startsWith("__mfb ")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        String[] parts = message.split(" ", -1);
        if (parts.length < 2) {
            sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "empty"));
            return;
        }
        try {
            switch (parts[1]) {
                case "hello" -> issueSession(player);
                case "place" -> {
                    if (!player.hasPermission("maxfastbuild.use")) {
                        sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
                        return;
                    }
                    if (!rateLimit(player)) {
                        sendProtocol(player, "error", "maxfastbuild.error.rate_limited", Map.of());
                        return;
                    }
                    CompactPlaceCommand.Intent intent = CompactPlaceCommand.parse(message);
                    handleClientRequest(player, new ClientRequest("place", intent.mode().name().toLowerCase(Locale.ROOT),
                            intent.first(), intent.second(), intent.hollow(), intent.material()));
                }
                case "break" -> {
                    if (!player.hasPermission("maxfastbuild.use")) {
                        sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
                        return;
                    }
                    if (!rateLimit(player)) {
                        sendProtocol(player, "error", "maxfastbuild.error.rate_limited", Map.of());
                        return;
                    }
                    CompactBreakCommand.Intent intent = CompactBreakCommand.parse(message);
                    handleClientRequest(player, new ClientRequest("break", intent.mode().name().toLowerCase(Locale.ROOT),
                            intent.first(), intent.second(), intent.hollow(), "minecraft:air"));
                }
                case "p" -> {
                    if (parts.length < 6) {
                        sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "chunk_arity"));
                        return;
                    }
                    String chunk = parts.length == 6 ? parts[5] : String.join(" ", Arrays.copyOfRange(parts, 5, parts.length));
                    Optional<String> complete = chunks.accept(player.getUniqueId(), parts[2], Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), chunk);
                    if (complete.isEmpty()) return;
                    if (!rateLimit(player)) {
                        sendProtocol(player, "error", "maxfastbuild.error.rate_limited", Map.of());
                        return;
                    }
                    String[] envelopeParts = complete.get().split(" ", 5);
                    if (envelopeParts.length != 5) throw new IllegalArgumentException("invalid_envelope");
                    ProtocolEnvelope envelope = new ProtocolEnvelope(Integer.parseInt(envelopeParts[0]), envelopeParts[1], Long.parseLong(envelopeParts[2]), envelopeParts[3], envelopeParts[4]);
                    byte[] json = protocol.verify(player.getUniqueId(), envelope);
                    ClientRequest request = GSON.fromJson(new String(json, StandardCharsets.UTF_8), ClientRequest.class);
                    if (!player.hasPermission("maxfastbuild.use")) {
                        sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
                        return;
                    }
                    handleClientRequest(player, request);
                }
                default -> sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "unknown_subcmd"));
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Internal protocol from " + player.getName() + ": " + ex.getMessage());
            sendProtocol(player, "error", "maxfastbuild.error.protocol", Map.of("reason", ex.getMessage() == null ? "invalid" : ex.getMessage()));
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (!active || tasks == null || executor == null) return;
        UUID playerId = event.getPlayer().getUniqueId();
        for (BuildTask task : tasks.recoverable()) {
            if (!task.playerId().equals(playerId)) continue;
            if (task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.QUEUED) continue;
            try {
                // Prefer in-memory snapshot (has latest applied_count) when present.
                BuildTask latest = tasks.find(task.id()).orElse(task);
                if (executor.isActive(task.id())) {
                    // Detach first so a concurrent tick cannot race; applied already flushed each tick.
                    executor.detach(task.id());
                    latest = tasks.find(task.id()).orElse(task);
                }
                if (latest.status() == TaskStatus.RUNNING || latest.status() == TaskStatus.QUEUED) {
                    tasks.save(latest.transition(TaskStatus.PAUSED_OFFLINE, Instant.now()));
                }
            } catch (RuntimeException ex) {
                getLogger().warning("Failed to pause task " + task.id() + " on quit: " + ex.getMessage());
            }
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!active || tasks == null || executor == null) return;
        for (BuildTask task : tasks.recoverable()) {
            if (!task.playerId().equals(event.getPlayer().getUniqueId())) continue;
            if (task.status() != TaskStatus.PAUSED_OFFLINE && task.status() != TaskStatus.PAUSED_SHUTDOWN) continue;
            if (executor.isActive(task.id())) continue;
            executor.enqueue(task.transition(TaskStatus.QUEUED, Instant.now()));
        }
    }

    void handlePublicCommand(Player player, String[] args) {
        if (!active || tasks == null || messages == null) {
            if (messages != null) messages.send(player, "not-ready");
            else player.sendMessage(Component.text("MaxFastBuild is not ready"));
            return;
        }
        if (args.length == 0 || "help".equalsIgnoreCase(args[0]) || "?".equals(args[0])) {
            sendPublicHelp(player);
            return;
        }
        Selection selection = selections.computeIfAbsent(player.getUniqueId(),
                ignored -> new Selection(BuildMode.LINE, null, null, false, "minecraft:stone"));
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "mode" -> {
                if (args.length < 2) {
                    messages.send(player, "mode-usage");
                    return;
                }
                BuildMode mode;
                try {
                    mode = BuildMode.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    messages.send(player, "mode-invalid", args[1], modeListLocalized());
                    return;
                }
                selections.put(player.getUniqueId(), selection.withMode(mode));
                messages.send(player, "mode-set", modeDisplayName(mode));
            }
            case "pos1" -> {
                BlockPos pos = at(player);
                selections.put(player.getUniqueId(), selection.withFirst(pos));
                messages.send(player, "pos1-set", formatPos(pos));
            }
            case "pos2" -> {
                BlockPos pos = at(player);
                selections.put(player.getUniqueId(), selection.withSecond(pos));
                messages.send(player, "pos2-set", formatPos(pos));
            }
            case "hollow" -> {
                boolean hollow;
                if (args.length < 2) {
                    hollow = !selection.hollow();
                } else if ("true".equalsIgnoreCase(args[1]) || "1".equals(args[1]) || "on".equalsIgnoreCase(args[1])) {
                    hollow = true;
                } else if ("false".equalsIgnoreCase(args[1]) || "0".equals(args[1]) || "off".equalsIgnoreCase(args[1])) {
                    hollow = false;
                } else {
                    messages.send(player, "hollow-usage");
                    return;
                }
                selections.put(player.getUniqueId(), selection.withHollow(hollow));
                messages.send(player, "hollow-set", hollow);
            }
            case "material" -> {
                if (args.length < 2) {
                    messages.send(player, "material-usage");
                    return;
                }
                String raw = args[1].contains(":") ? args[1] : "minecraft:" + args[1];
                Material mat = PaperInventoryHelper.resolveMaterial(raw);
                if (mat == null || PaperWorldAccess.isForbiddenPlaceMaterial(mat)) {
                    messages.send(player, "material-invalid", raw);
                    return;
                }
                String key = mat.getKey().toString();
                selections.put(player.getUniqueId(), selection.withMaterial(key));
                messages.send(player, "material-set", key);
            }
            case "apply" -> {
                // Same hand rules as /__mfb / Fabric client: block→place, tool→break, empty→reject.
                Selection current = selections.getOrDefault(player.getUniqueId(), selection);
                if (args.length >= 2) {
                    // Legacy args ignored for operation; hand is source of truth (same as client mod).
                    messages.send(player, "apply-hand-hint");
                }
                if (!rateLimit(player)) {
                    notifyPlayer(player, "error", "maxfastbuild.error.rate_limited", Map.of());
                    return;
                }
                submitFromHand(player, current);
            }
            case "status" -> {
                Selection current = selections.getOrDefault(player.getUniqueId(), selection);
                String missing = plain(messages.raw("status-pos-missing"));
                String p1 = current.first() == null ? missing : formatPos(current.first());
                String p2 = current.second() == null ? missing : formatPos(current.second());
                messages.send(player, "status-selection",
                        modeDisplayName(current.mode()),
                        current.hollow(),
                        current.material());
                messages.send(player, "status-pos", p1, p2);
                messages.send(player, "status-tasks", tasks.activeCount(player.getUniqueId()));
            }
            case "cancel" -> cancelPlayerTasks(player);
            case "about" -> messages.send(player, "about");
            default -> messages.send(player, "unknown-subcommand", args[0]);
        }
    }

    private void sendPublicHelp(Player player) {
        messages.send(player, "help-header");
        messages.send(player, "help-line-help");
        messages.send(player, "help-line-mode");
        messages.send(player, "help-line-pos1");
        messages.send(player, "help-line-pos2");
        messages.send(player, "help-line-material");
        messages.send(player, "help-line-hollow");
        messages.send(player, "help-line-apply");
        messages.send(player, "help-line-status");
        messages.send(player, "help-line-cancel");
        messages.send(player, "help-line-about");
        messages.send(player, "help-modes");
        messages.send(player, "help-tip");
    }

    private static String formatPos(BlockPos pos) {
        return pos.x() + ", " + pos.y() + ", " + pos.z();
    }

    private static String plain(String miniOrLegacy) {
        if (miniOrLegacy == null || miniOrLegacy.isBlank()) return "-";
        return miniOrLegacy.replaceAll("<[^>]+>", "").replace('&', '§').replaceAll("§.", "");
    }

    private void handleClientRequest(Player player, ClientRequest request) {
        BuildMode mode = BuildMode.valueOf(request.mode().toUpperCase(Locale.ROOT));
        // Same hand resolution as /mfb apply (HandIntent) — protocol op/material are not trusted.
        Selection anchors = new Selection(mode, request.first(), request.second(), request.hollow(),
                request.material() == null ? "minecraft:stone" : request.material());
        submitFromHand(player, anchors, false);
    }

    /** /mfb apply and /__mfb — resolve place/break from main hand. */
    private void submitFromHand(Player player, Selection selection) {
        submitFromHand(player, selection, true);
    }

    private void submitFromHand(Player player, Selection selection, boolean cliFeedback) {
        HandIntent intent = HandIntent.from(player);
        if (intent.isNone()) {
            if (cliFeedback && messages != null) messages.send(player, "apply-need-hand");
            notifyPlayer(player, "error", "maxfastbuild.error.hold_block_or_tool",
                    Map.of("reason", intent.rejectReason() == null ? "none" : intent.rejectReason()));
            return;
        }
        Selection effective = selection;
        if (intent.isPlace()) {
            effective = selection.withMaterial(intent.material());
            selections.put(player.getUniqueId(), effective);
            if (cliFeedback && messages != null) {
                messages.send(player, "apply-place-from-hand", intent.material());
            }
        } else {
            selections.put(player.getUniqueId(), effective);
            if (cliFeedback && messages != null) {
                messages.send(player, "apply-break-from-hand");
            }
        }
        submit(player, effective, intent.operation());
    }

    private String modeDisplayName(BuildMode mode) {
        String key = "mode-" + mode.name().toLowerCase(Locale.ROOT);
        String labeled = messages != null ? messages.raw(key) : null;
        if (labeled != null && !labeled.isBlank()) {
            return plain(labeled) + " (" + mode.name().toLowerCase(Locale.ROOT) + ")";
        }
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private String modeListLocalized() {
        StringBuilder sb = new StringBuilder();
        for (BuildMode mode : BuildMode.values()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(modeDisplayName(mode));
        }
        return sb.toString();
    }

    private void submit(Player player, Selection selection, OperationKind operation) {
        if (!player.hasPermission("maxfastbuild.use")) {
            sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
            return;
        }
        if (selection.first() == null || selection.second() == null) {
            sendProtocol(player, "error", "maxfastbuild.error.positions_required", Map.of());
            return;
        }
        if (getConfig().getBoolean("coreprotect.required", true) && !audit.available()) {
            sendProtocol(player, "error", "maxfastbuild.error.coreprotect_unavailable", Map.of());
            return;
        }
        if (getConfig().getBoolean("economy.enabled") && !economy.enabled() && !player.hasPermission("maxfastbuild.bypass.cost")) {
            sendProtocol(player, "error", "maxfastbuild.error.economy_unavailable", Map.of());
            return;
        }
        int max = getConfig().getInt("execution.max-region-blocks", 100000);
        if (tasks.activeCount(player.getUniqueId()) >= getConfig().getInt("execution.max-concurrent-tasks-per-player", 2)) {
            sendProtocol(player, "error", "maxfastbuild.error.task_limit", Map.of());
            return;
        }

        Set<BlockPos> positions;
        try {
            positions = new DefaultShapeGenerator().generate(
                    new ShapeRequest(selection.mode(), selection.first(), selection.second(), selection.hollow()), max);
        } catch (ShapeLimitException ex) {
            sendProtocol(player, "error", "maxfastbuild.error.shape_too_large", Map.of("limit", max));
            return;
        } catch (RuntimeException ex) {
            sendProtocol(player, "error", "maxfastbuild.error.protocol", Map.of("reason", ex.getMessage() == null ? "shape" : ex.getMessage()));
            return;
        }

        PaperWorldAccess world = new PaperWorldAccess();
        List<BlockMutation> mutations = new ArrayList<>();
        long replaceBreakCount = 0;
        for (BlockPos pos : positions) {
            if (pos.y() < player.getWorld().getMinHeight() || pos.y() >= player.getWorld().getMaxHeight()) {
                sendProtocol(player, "error", "maxfastbuild.error.protected", Map.of("position", pos.toString(), "reason", "unsafe_height"));
                return;
            }
            String before = world.stateAt(player.getWorld().getName(), pos);
            if (operation == OperationKind.BREAK && before.equals("minecraft:air")) continue;
            BlockMutation mutation = new BlockMutation(pos, before, operation == OperationKind.BREAK ? "minecraft:air" : selection.material());
            WorldAccess.ValidationResult validation = world.mayMutate(player.getUniqueId(), player.getWorld().getName(), mutation, operation);
            if (!validation.allowed()) {
                if ("insufficient_tool".equals(validation.reason())) {
                    sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool", Map.of("reason", validation.reason()));
                    return;
                }
                if ("unbreakable_block".equals(validation.reason()) || "unbreakable_replace".equals(validation.reason())) {
                    sendProtocol(player, "error", "maxfastbuild.error.unbreakable_block",
                            Map.of("position", pos.toString(), "block", before, "reason", validation.reason()));
                    return;
                }
                if ("forbidden_material".equals(validation.reason())) {
                    sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", String.valueOf(selection.material())));
                    return;
                }
                sendProtocol(player, "error", "maxfastbuild.error.protected", Map.of("position", pos.toString(), "reason", validation.reason()));
                return;
            }
            if (!before.equals(mutation.targetState())) {
                mutations.add(mutation);
                if (operation == OperationKind.PLACE && PaperWorldAccess.requiresBreakToReplace(before)) {
                    replaceBreakCount++;
                }
            }
        }
        if (mutations.isEmpty()) { sendProtocol(player, "error", "maxfastbuild.error.no_changes", Map.of()); return; }

        // Break mode, or place-over-solid: survival needs effective tools for every break target.
        boolean needsBreakTools = operation == OperationKind.BREAK
                || (operation == OperationKind.PLACE && replaceBreakCount > 0);
        if (needsBreakTools && player.getGameMode() != GameMode.CREATIVE) {
            if (!BreakToolHelper.hasAnyMiningTool(player)) {
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool", Map.of("reason", "no_tool"));
                return;
            }
            for (BlockMutation mutation : mutations) {
                if (operation == OperationKind.PLACE && !PaperWorldAccess.requiresBreakToReplace(mutation.expectedState())) {
                    continue;
                }
                org.bukkit.block.Block target = player.getWorld().getBlockAt(
                        mutation.position().x(), mutation.position().y(), mutation.position().z());
                if (!BreakToolHelper.canBreakBlock(player, target)) {
                    sendProtocol(player, "error", "maxfastbuild.error.wrong_tool",
                            Map.of("block", target.getType().getKey().toString(),
                                    "reason", "no_effective_tool"));
                    return;
                }
            }
            // Durability budget: each solid replace wears a tool once (MIN_REMAINING floor already in helper).
            if (operation == OperationKind.PLACE && replaceBreakCount > 0) {
                long usable = estimateUsableToolHits(player);
                if (usable < replaceBreakCount) {
                    sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool_durability",
                            Map.of("reason", "durability", "need", replaceBreakCount, "have", usable));
                    return;
                }
            }
        }

        BuildPlan plan = new BuildPlan(player.getWorld().getName(), operation, new Bounds(selection.first(), selection.second()), mutations);
        // Place-over-solid: charge per-block for place + for each required break.
        BillingPolicy.Charge charge = billing().quote(plan, operation == OperationKind.PLACE ? replaceBreakCount : 0);
        boolean requireMaterials = operation == OperationKind.PLACE
                && player.getGameMode() != GameMode.CREATIVE
                && !player.hasPermission("maxfastbuild.bypass.materials");
        boolean searchShulkers = getConfig().getBoolean("inventory.search-shulker-boxes", false);
        if (searchShulkers && getConfig().getBoolean("inventory.require-shulker-permission", false)
                && !player.hasPermission("maxfastbuild.material.shulker")) {
            searchShulkers = false;
        }
        String itemKey = PaperInventoryHelper.itemKeyFromBlockState(selection.material());
        long need = mutations.size();
        if (requireMaterials) {
            org.bukkit.Material resolved = PaperInventoryHelper.resolveMaterial(itemKey);
            if (resolved == null || PaperWorldAccess.isForbiddenPlaceMaterial(resolved)) {
                sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", String.valueOf(selection.material())));
                return;
            }
            long have = PaperInventoryHelper.count(player, itemKey, searchShulkers);
            if (have < need) {
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_materials",
                        Map.of("need", need, "have", have, "material", itemKey));
                return;
            }
        }

        UUID taskId = UUID.randomUUID();
        String transactionId = taskId + ":withdraw";
        boolean tookMoney = false;
        boolean tookItems = false;
        if (charge.total().signum() > 0 && !player.hasPermission("maxfastbuild.bypass.cost")) {
            if (getConfig().getBoolean("economy.enabled") && !economy.enabled()) {
                sendProtocol(player, "error", "maxfastbuild.error.economy_unavailable", Map.of());
                return;
            }
            ledger.intent(transactionId, taskId, player.getUniqueId(), EconomyLedger.Kind.WITHDRAW, charge.total());
            EconomyService.TransactionResult result = economy.withdraw(player.getUniqueId(), charge.total(), transactionId);
            ledger.complete(transactionId, taskId, player.getUniqueId(), EconomyLedger.Kind.WITHDRAW, charge.total(), result.successful(), result.message());
            if (!result.successful()) {
                sendProtocol(player, "error", "maxfastbuild.error.payment_failed", Map.of("reason", result.message() == null ? "failed" : result.message()));
                return;
            }
            tookMoney = true;
        }
        if (requireMaterials) {
            long removed = PaperInventoryHelper.take(player, itemKey, need, searchShulkers);
            if (removed < need) {
                if (removed > 0) PaperInventoryHelper.giveOrDrop(player, itemKey, removed);
                if (tookMoney) refundMoney(player, taskId, charge.total(), transactionId);
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_materials",
                        Map.of("need", need, "have", removed, "material", itemKey));
                return;
            }
            tookItems = true;
        }

        Instant now = Instant.now();
        BuildTask task = new BuildTask(taskId, player.getUniqueId(), player.getName(), plan, TaskStatus.QUEUED,
                0, 0, null, charge.total(), BigDecimal.ZERO, now, now, null);
        try {
            executor.enqueue(task);
        } catch (RuntimeException ex) {
            if (tookItems) PaperInventoryHelper.giveOrDrop(player, itemKey, need);
            compensate(player, taskId, tookMoney ? charge.total() : BigDecimal.ZERO, transactionId, ex);
            return;
        }
        sendProtocol(player, "accepted", "maxfastbuild.task.accepted", Map.of(
                "taskId", taskId.toString(),
                "blocks", mutations.size(),
                "charge", charge.total().toPlainString()));
    }

    private void refundMoney(Player player, UUID taskId, BigDecimal amount, String transactionId) {
        if (amount == null || amount.signum() <= 0) return;
        String refundId = transactionId + ":compensation";
        ledger.intent(refundId, taskId, player.getUniqueId(), EconomyLedger.Kind.REFUND, amount);
        EconomyService.TransactionResult result = economy.deposit(player.getUniqueId(), amount, refundId);
        ledger.complete(refundId, taskId, player.getUniqueId(), EconomyLedger.Kind.REFUND, amount, result.successful(), result.message());
    }

    private void compensate(Player player, UUID taskId, BigDecimal amount, String transactionId, RuntimeException cause) {
        refundMoney(player, taskId, amount, transactionId);
        sendProtocol(player, "error", "maxfastbuild.error.persistence_failed", Map.of("reason", cause.getMessage() == null ? "failed" : cause.getMessage()));
    }

    private void tickTasks() {
        if (!active || tasks == null || executor == null) return;
        int count = Math.max(1, getConfig().getInt("execution.blocks-per-step", 1));
        for (BuildTask task : tasks.recoverable()) {
            if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.RUNNING) continue;
            if (Bukkit.getPlayer(task.playerId()) == null) continue;
            if (!executor.isActive(task.id())) {
                // DB says active but memory was detached (quit/shutdown) — reattach when player is online.
                try {
                    executor.enqueue(task.status() == TaskStatus.QUEUED ? task : task.transition(TaskStatus.QUEUED, Instant.now()));
                } catch (RuntimeException ex) {
                    getLogger().warning("Reattach task " + task.id() + " failed: " + ex.getMessage());
                    continue;
                }
            }
            try {
                TaskExecutor.TickResult result = executor.tick(task.id(), count);
                if (result.finished()) settlePartial(result);
            } catch (RuntimeException ex) {
                getLogger().severe("Task " + task.id() + " failed: " + ex.getMessage());
            }
        }
    }

    /**
     * After a task finishes or is cancelled: refund per-block/per-area for mutations that never applied,
     * and return unused place materials. Fixed per-operation fee is not refunded once execution started.
     * Applied count is read from the persisted task (restart-safe).
     */
    private void settlePartial(TaskExecutor.TickResult result) {
        BuildTask task = result.task();
        long planned = task.plan().mutations().size();
        long appliedCount = Math.max(task.appliedCount(), result.totalApplied());
        long missed = Math.max(0, planned - appliedCount);

        BillingPolicy policy = billing();
        long replaceBreaks = 0;
        if (task.plan().operation() == OperationKind.PLACE) {
            for (BlockMutation mutation : task.plan().mutations()) {
                if (PaperWorldAccess.requiresBreakToReplace(mutation.expectedState())) replaceBreaks++;
            }
        }
        BigDecimal areaPart = policy.perAreaEnabled()
                ? policy.perArea().multiply(BigDecimal.valueOf(task.plan().bounds().maximumPlaneArea()))
                : BigDecimal.ZERO;
        BigDecimal blockPart = policy.perBlockEnabled()
                ? policy.perBlock().multiply(BigDecimal.valueOf(planned + replaceBreaks))
                : BigDecimal.ZERO;
        BigDecimal operationPart = BigDecimal.ZERO;
        if (appliedCount == 0 && policy.perOperationEnabled()) {
            operationPart = policy.perOperation();
        }
        BigDecimal refund = policy.refund(
                new BillingPolicy.Charge(operationPart, areaPart, blockPart, task.charged()),
                planned, appliedCount, replaceBreaks);
        if (appliedCount == 0 && operationPart.signum() > 0) {
            refund = refund.add(operationPart).setScale(policy.fractionalDigits(), java.math.RoundingMode.HALF_UP);
        }

        Player player = Bukkit.getPlayer(task.playerId());
        if (refund.signum() > 0) {
            String tx = task.id() + ":partial-refund";
            if (player != null) {
                refundMoney(player, task.id(), refund, tx);
            } else {
                ledger.intent(tx + ":compensation", task.id(), task.playerId(), EconomyLedger.Kind.REFUND, refund);
                EconomyService.TransactionResult dep = economy.deposit(task.playerId(), refund, tx + ":compensation");
                ledger.complete(tx + ":compensation", task.id(), task.playerId(), EconomyLedger.Kind.REFUND, refund, dep.successful(), dep.message());
            }
            tasks.save(task.withRefunded(task.refunded().add(refund), Instant.now()));
        }

        if (task.plan().operation() == OperationKind.PLACE && missed > 0) {
            returnPlaceMaterials(player, task, missed);
        }

        if (player != null) {
            String key = missed > 0 ? "maxfastbuild.task.partial" : "maxfastbuild.task.completed";
            sendProtocol(player, "completed", key,
                    Map.of("applied", appliedCount, "planned", planned, "refund", refund.toPlainString()));
        }
    }

    private void returnPlaceMaterials(Player player, BuildTask task, long count) {
        if (task.plan().mutations().isEmpty() || count <= 0) return;
        if (player != null && player.getGameMode() == GameMode.CREATIVE) return;
        String itemKey = PaperInventoryHelper.itemKeyFromBlockState(task.plan().mutations().getFirst().targetState());
        if (player != null) {
            PaperInventoryHelper.giveOrDrop(player, itemKey, count);
            return;
        }
        // Offline: drop near first mutation so materials are not silently lost.
        org.bukkit.Material mat = PaperInventoryHelper.resolveMaterial(itemKey);
        if (mat == null || !mat.isItem()) return;
        World world = Bukkit.getWorld(task.plan().world());
        if (world == null) return;
        BlockPos pos = task.plan().mutations().getFirst().position();
        Location loc = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
        long left = count;
        while (left > 0) {
            int stack = (int) Math.min(left, mat.getMaxStackSize());
            world.dropItemNaturally(loc, new org.bukkit.inventory.ItemStack(mat, stack));
            left -= stack;
        }
    }

    private void resumeTasks() {
        if (tasks == null || executor == null) return;
        for (BuildTask task : tasks.recoverable()) {
            TaskStatus status = task.status();
            if (status != TaskStatus.RUNNING && status != TaskStatus.PAUSED_SHUTDOWN && status != TaskStatus.QUEUED) continue;
            if (Bukkit.getPlayer(task.playerId()) == null) continue;
            if (executor.isActive(task.id())) continue;
            BuildTask queued = status == TaskStatus.QUEUED ? task : task.transition(TaskStatus.QUEUED, Instant.now());
            executor.enqueue(queued);
        }
    }

    private boolean handleAdmin(org.bukkit.command.CommandSender sender, String[] args) {
        if (!active || tasks == null || ledger == null || messages == null) {
            if (messages != null) messages.send(sender, "not-ready");
            else sender.sendMessage("MaxFastBuild is not ready");
            return true;
        }
        if (!sender.hasPermission("maxfastbuild.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            messages.send(sender, "admin-help-header");
            messages.send(sender, "admin-help-reload");
            messages.send(sender, "admin-help-recovery");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            messages.reload();
            sender.sendMessage(messages.component("reloaded"));
            getLogger().info("Reloaded config; CLI language=" + messages.language());
        } else if (args[0].equalsIgnoreCase("recovery")) {
            messages.send(sender, "recovery", tasks.recoverable().size(), ledger.pending().size());
        } else {
            messages.send(sender, "admin-unknown");
        }
        return true;
    }

    private void cancelPlayerTasks(Player player) {
        if (!active || tasks == null || executor == null) return;
        int cancelled = 0;
        for (BuildTask task : tasks.recoverable()) {
            if (!task.playerId().equals(player.getUniqueId())) continue;
            if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.RUNNING
                    && task.status() != TaskStatus.PAUSED_OFFLINE && task.status() != TaskStatus.PAUSED_SHUTDOWN) continue;
            try {
                TaskExecutor.TickResult aborted = executor.abort(task.id());
                settlePartial(aborted);
                cancelled++;
            } catch (RuntimeException ex) {
                try {
                    BuildTask cancelling = task.transition(TaskStatus.CANCELLING, Instant.now());
                    tasks.save(cancelling);
                    int applied = task.appliedCount();
                    settlePartial(new TaskExecutor.TickResult(
                            cancelling.transition(TaskStatus.CANCELLED, Instant.now()), 0, 0, applied, true));
                    cancelled++;
                } catch (RuntimeException inner) {
                    getLogger().warning("Cancel settle failed for " + task.id() + ": " + inner.getMessage());
                }
            }
        }
        if (messages != null) {
            if (cancelled == 0) messages.send(player, "cancel-none");
            else messages.send(player, "cancel-done", cancelled);
        }
    }

    /** Total mining hits available across inventory tools (respects durability floor). */
    private static long estimateUsableToolHits(Player player) {
        long total = 0;
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            org.bukkit.inventory.ItemStack stack = inv.getItem(slot);
            if (stack != null) total += BreakToolHelper.remainingUses(stack);
        }
        org.bukkit.inventory.ItemStack off = inv.getItemInOffHand();
        if (off != null) total += BreakToolHelper.remainingUses(off);
        return total;
    }

    private BillingPolicy billing() {
        return new BillingPolicy(getConfig().getBoolean("economy.per-operation.enabled"), decimal("economy.per-operation.price"), getConfig().getBoolean("economy.per-area.enabled"), decimal("economy.per-area.price"), getConfig().getBoolean("economy.per-block.enabled"), decimal("economy.per-block.price"), 2);
    }

    private BigDecimal decimal(String path) { return new BigDecimal(String.valueOf(getConfig().get(path, "0"))); }
    private EconomyService discoverEconomy() {
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration != null) return new VaultEconomyService(registration.getProvider());
        return unavailableEconomy();
    }

    private EconomyService safeDiscoverEconomy() {
        try { return discoverEconomy(); }
        catch (LinkageError error) {
            getLogger().info("Vault API is not installed; economy integration is disabled");
            return unavailableEconomy();
        }
    }

    private EconomyService unavailableEconomy() {
        return new EconomyService() {
            public TransactionResult withdraw(UUID id, BigDecimal amount, String tx) { return new TransactionResult(false, "economy_unavailable"); }
            public TransactionResult deposit(UUID id, BigDecimal amount, String tx) { return new TransactionResult(false, "economy_unavailable"); }
            public boolean enabled() { return false; }
        };
    }

    private AuditService safeDiscoverAudit() {
        try { return CoreProtectAuditService.discover(); }
        catch (LinkageError error) {
            getLogger().info("CoreProtect API is not installed; audit integration is disabled");
            return new AuditService() {
                public boolean available() { return false; }
                public void record(UUID playerId, String playerName, String world, BlockMutation mutation, OperationKind kind) {}
                public void record(UUID playerId, String playerName, String world, BlockMutation mutation, OperationKind kind, boolean breakAlreadyLogged) {}
            };
        }
    }

    private void validateIntegrations() {
        if (getConfig().getBoolean("coreprotect.required", true) && !audit.available()) getLogger().severe("CoreProtect is required; build requests will be rejected until available");
        if (getConfig().getBoolean("economy.enabled") && !economy.enabled()) getLogger().severe("Economy is enabled but Vault has no provider; paid requests will be rejected");
    }

    private boolean rateLimit(Player player) {
        if (player.hasPermission("maxfastbuild.bypass.rate-limit")) return true;
        int requests = getConfig().getInt("rate-limit.requests", 3), burst = getConfig().getInt("rate-limit.burst", 1);
        long interval = getConfig().getLong("rate-limit.interval-seconds", 10) * 1000;
        return limits.computeIfAbsent(player.getUniqueId(), ignored -> new TokenBucket(requests + burst, requests, interval, Clock.systemUTC())).tryAcquire();
    }

    private void issueSession(Player player) {
        // Legacy HMAC path only. Secret is sent once over the marked system channel (same trust as other protocol traffic).
        // Compact place/break do not use this session; prefer those for normal clients.
        SecureProtocol.Session session = protocol.issue(player.getUniqueId());
        sessions.put(player.getUniqueId(), session);
        sendMarked(player, GSON.toJson(Map.of(
                "mfb", 1,
                "type", "hello",
                "sessionId", session.id(),
                "secret", Base64.getUrlEncoder().withoutPadding().encodeToString(session.secret()),
                "expiresAt", session.expiresAt().toString())));
    }

    /**
     * Protocol channel for Fabric client + human-readable CLI line (default Chinese).
     * Fabric client hides the marked system message and shows its own translation.
     */
    private void sendProtocol(Player player, String type, String key, Map<String, ?> data) {
        notifyPlayer(player, type, key, data);
    }

    private void notifyPlayer(Player player, String type, String key, Map<String, ?> data) {
        Map<String, ?> safe = data == null ? Map.of() : data;
        sendMarked(player, GSON.toJson(Map.of("mfb", 1, "type", type, "messageKey", key, "data", safe)));
        // Always also send readable feedback for pure command users / chat logs without Fabric.
        if (messages != null) {
            player.sendMessage(messages.fromProtocol(key, safe));
        }
    }

    private void sendMarked(Player player, String json) {
        player.sendMessage(Component.text(ProtocolEnvelope.MESSAGE_MARKER + json));
    }
    private static BlockPos at(Player player) { Location p = player.getLocation(); return new BlockPos(p.getBlockX(), p.getBlockY(), p.getBlockZ()); }

    private record ClientRequest(String operation, String mode, BlockPos first, BlockPos second, boolean hollow, String material) {}
    private record Selection(BuildMode mode, BlockPos first, BlockPos second, boolean hollow, String material) {
        Selection withMode(BuildMode value) { return new Selection(value, first, second, hollow, material); }
        Selection withFirst(BlockPos value) { return new Selection(mode, value, second, hollow, material); }
        Selection withSecond(BlockPos value) { return new Selection(mode, first, value, hollow, material); }
        Selection withHollow(boolean value) { return new Selection(mode, first, second, value, material); }
        Selection withMaterial(String value) { return new Selection(mode, first, second, hollow, value); }
    }
}
