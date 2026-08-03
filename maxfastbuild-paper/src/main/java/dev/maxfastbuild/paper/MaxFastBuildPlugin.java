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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private final Map<UUID, PendingBuild> pendingBuilds = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPaste> pendingPastes = new ConcurrentHashMap<>();
    private final Map<UUID, Queue<QueuedCommand>> commandQueues = new ConcurrentHashMap<>();
    private final CommandChunkAssembler chunks = new CommandChunkAssembler(Clock.systemUTC(), Duration.ofSeconds(15));
    /** Reassembles multi-part Litematica paste payloads (session per player+id, 120s window). */
    private final PasteAccumulator pastes = new PasteAccumulator(Clock.systemUTC(), Duration.ofSeconds(120));
    private SecureProtocol protocol;
    private SqliteDatabase database;
    private TaskRepository tasks;
    private EconomyLedger ledger;
    private TaskExecutor executor;
    private EconomyService economy;
    private AuditService audit;
    private PluginMessages messages;
    private int globalBudgetPerTick;
    private int planningGlobalBudgetPerTick;
    /** Guards handlers and ticks during PlugMan unload / failed enable. */
    private volatile boolean active;

    PluginMessages messages() {
        return messages;
    }

    @Override public void onEnable() {
        active = false;
        saveDefaultConfig();
        mergeConfigDefaults();
        messages = new PluginMessages(this);
        messages.reload();
        try {
            protocol = new SecureProtocol(Clock.systemUTC(), Duration.ofMinutes(getConfig().getLong("protocol.session-minutes", 30)), getConfig().getInt("protocol.max-payload-bytes", 16384));
            database = new SqliteDatabase(getDataFolder().toPath().resolve("maxfastbuild.db"));
            SqliteTaskRepository sqliteTasks = new SqliteTaskRepository(database);
            sqliteTasks.initialize();
            if (getConfig().getBoolean("execution.async-queue.enabled", true)) {
                int batchSize = Math.max(1, getConfig().getInt("execution.async-queue.batch-size", 10));
                long maxDelayMs = Math.max(1, getConfig().getLong("execution.async-queue.max-delay-ms", 200));
                tasks = new AsyncTaskRepository(sqliteTasks, batchSize, maxDelayMs);
            } else {
                tasks = sqliteTasks;
            }
            ledger = new EconomyLedger(database, true);
            ledger.initialize();
            audit = safeDiscoverAudit();
            economy = safeDiscoverEconomy();
            int saveInterval = Math.max(1, getConfig().getInt("execution.save-interval-ticks", 20));
            executor = new TaskExecutor(tasks, new PaperWorldAccess(), audit, Clock.systemUTC(), saveInterval);
            globalBudgetPerTick = Math.max(0, getConfig().getInt("execution.global-blocks-per-tick", 4));
            planningGlobalBudgetPerTick = Math.max(0, getConfig().getInt("execution.planning.global-blocks-per-tick", 2000));
            validateIntegrations();

            PublicCommand publicCommand = new PublicCommand(this);
            PluginCommand mfb = Objects.requireNonNull(getCommand("mfb"), "mfb command missing from plugin.yml");
            mfb.setExecutor(publicCommand);
            mfb.setTabCompleter(publicCommand);
            PluginCommand mfbAdmin = Objects.requireNonNull(getCommand("mfbadmin"), "mfbadmin command missing from plugin.yml");
            mfbAdmin.setExecutor((sender, command, label, args) -> handleAdmin(sender, args));
            FillCommand fillCommand = new FillCommand(this);
            PluginCommand mfbFill = Objects.requireNonNull(getCommand("mfbfill"), "mfbfill command missing from plugin.yml");
            mfbFill.setExecutor(fillCommand);
            mfbFill.setTabCompleter(fillCommand);
            SetBlockCommand setBlockCommand = new SetBlockCommand(this);
            PluginCommand mfbSetblock = Objects.requireNonNull(getCommand("mfbsetblock"), "mfbsetblock command missing from plugin.yml");
            mfbSetblock.setExecutor(setBlockCommand);
            mfbSetblock.setTabCompleter(setBlockCommand);
            getServer().getPluginManager().registerEvents(this, this);
            resumeTasks();
            long period = Math.max(1, getConfig().getLong("execution.ticks-per-block", 1));
            getServer().getScheduler().runTaskTimer(this, () -> { tickPlanners(); tickPastePlanners(); tickTasks(); processCommandQueues(); }, period, period);
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

    /**
     * Copies bundled config keys missing from an existing on-disk config.yml so new options
     * appear after updates without clobbering operator customizations. Only rewrites the file
     * when something new was added (preserves comments otherwise).
     */
    private void mergeConfigDefaults() {
        java.io.File file = new java.io.File(getDataFolder(), "config.yml");
        InputStream stream = getResource("config.yml");
        if (!file.isFile() || stream == null) return;
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            FileConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (!config.contains(key)) {
                    config.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) {
                config.save(file);
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to merge config defaults: " + ex.getMessage());
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
            pendingBuilds.clear();
            pendingPastes.clear();
            commandQueues.clear();
            try { chunks.clear(); } catch (RuntimeException ignored) { }
            try { pastes.clear(); } catch (RuntimeException ignored) { }
            if (tasks != null) {
                try { tasks.closeQuietly(); } catch (RuntimeException ex) {
                    getLogger().warning("SQLite close failed: " + ex.getMessage());
                }
            }
            tasks = null;
            if (ledger != null) {
                try { ledger.close(); } catch (RuntimeException ex) {
                    getLogger().warning("Ledger close failed: " + ex.getMessage());
                }
            }
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
                    CompactPlaceCommand.Intent intent = CompactPlaceCommand.parse(message);
                    handleClientRequest(player, new ClientRequest("place", intent.mode().name().toLowerCase(Locale.ROOT),
                            intent.first(), intent.second(), intent.hollow(), intent.material()));
                }
                case "break" -> {
                    if (!player.hasPermission("maxfastbuild.use")) {
                        sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
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
                    String[] envelopeParts = complete.get().split(" ", 5);
                    if (envelopeParts.length != 5) throw new IllegalArgumentException("invalid_envelope");
                    ProtocolEnvelope envelope = new ProtocolEnvelope(Integer.parseInt(envelopeParts[0]), envelopeParts[1], Long.parseLong(envelopeParts[2]), envelopeParts[3], envelopeParts[4]);
                    byte[] verified = protocol.verify(player.getUniqueId(), envelope);
                    if (isGzipPayload(verified)) {
                        PasteTransfer.Payload paste = PasteTransfer.decode(PasteTransfer.gunzip(verified));
                        handlePastePayload(player, paste);
                        return;
                    }
                    ClientRequest request = GSON.fromJson(new String(verified, StandardCharsets.UTF_8), ClientRequest.class);
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
        pendingBuilds.remove(playerId);
        commandQueues.remove(playerId);
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
                ignored -> new Selection(BuildMode.LINE, null, null, false, "minecraft:stone", player.getWorld().getName()));
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
                selections.put(player.getUniqueId(), selection.withFirst(pos).withWorld(player.getWorld().getName()));
                messages.send(player, "pos1-set", formatPos(pos));
            }
            case "pos2" -> {
                BlockPos pos = at(player);
                selections.put(player.getUniqueId(), selection.withSecond(pos).withWorld(player.getWorld().getName()));
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
                submitFromHand(player, current);
            }
            case "setblock" -> submitSetBlock(player, args);
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
                messages.send(player, "status-tasks", executor.activeCount(player.getUniqueId()));
                messages.send(player, "status-queue", queueSize(player.getUniqueId()));
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

    private int queueSize(UUID playerId) {
        Queue<QueuedCommand> queue = commandQueues.get(playerId);
        return queue == null ? 0 : queue.size();
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
                request.material() == null ? "minecraft:stone" : request.material(), player.getWorld().getName());
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

    /** /mfbsetblock — standalone entry; routes into the /mfb setblock pipeline. */
    void handleSetBlockCommand(Player player, String[] args) {
        String[] routed = new String[args.length + 1];
        routed[0] = "setblock";
        System.arraycopy(args, 0, routed, 1, args.length);
        submitSetBlock(player, routed);
    }

    private void submitSetBlock(Player player, String[] args) {
        if (args.length < 5) {
            messages.send(player, "setblock-usage");
            return;
        }
        String mode = "replace";
        if (args.length >= 6) {
            mode = args[5].toLowerCase(Locale.ROOT);
            if (!mode.equals("replace") && !mode.equals("destroy") && !mode.equals("keep")) {
                messages.send(player, "setblock-invalid-mode", mode);
                return;
            }
        }

        // Parse position (supports ~ ~ ~ relative to player's feet)
        BlockPos position;
        try {
            Location loc = player.getLocation();
            int x = parseCoordinate(args[1], loc.getBlockX());
            int y = parseCoordinate(args[2], loc.getBlockY());
            int z = parseCoordinate(args[3], loc.getBlockZ());
            position = new BlockPos(x, y, z);
        } catch (NumberFormatException ex) {
            messages.send(player, "setblock-invalid-pos", Arrays.toString(Arrays.copyOfRange(args, 1, 4)));
            return;
        }

        // Parse block state
        String blockState = args[4];
        if (!blockState.contains(":")) {
            blockState = "minecraft:" + blockState;
        }
        BlockData blockData;
        try {
            blockData = Bukkit.createBlockData(blockState);
        } catch (IllegalArgumentException ex) {
            messages.send(player, "setblock-invalid-block", blockState);
            return;
        }
        Material material = blockData.getMaterial();
        if (material == null || !material.isBlock() || material.isAir() || RestrictedMaterials.isForbiddenPlace(material)) {
            messages.send(player, "setblock-invalid-block", blockState);
            return;
        }

        // Check permissions
        if (!player.hasPermission("maxfastbuild.use")) {
            sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
            return;
        }

        // Check height
        if (position.y() < player.getWorld().getMinHeight() || position.y() >= player.getWorld().getMaxHeight()) {
            sendProtocol(player, "error", "maxfastbuild.error.protected", Map.of("position", position.toString(), "reason", "unsafe_height"));
            return;
        }

        // Check CoreProtect
        if (getConfig().getBoolean("coreprotect.required", true) && !audit.available()) {
            sendProtocol(player, "error", "maxfastbuild.error.coreprotect_unavailable", Map.of());
            return;
        }

        // Check economy enabled
        if (getConfig().getBoolean("economy.enabled") && !economy.enabled() && !player.hasPermission("maxfastbuild.bypass.cost")) {
            sendProtocol(player, "error", "maxfastbuild.error.economy_unavailable", Map.of());
            return;
        }

        // Check task limit
        if (executor.activeCount(player.getUniqueId()) >= getConfig().getInt("execution.max-concurrent-tasks-per-player", 2)) {
            sendProtocol(player, "error", "maxfastbuild.error.task_limit", Map.of());
            return;
        }

        PaperWorldAccess world = new PaperWorldAccess();
        String worldName = player.getWorld().getName();
        String before = world.stateAt(worldName, position);

        // Mode: keep - only place if air/replaceable
        if ("keep".equals(mode) && !PaperWorldAccess.isReplaceableOccupant(Bukkit.createBlockData(before).getMaterial())) {
            messages.send(player, "setblock-success", formatPos(position), blockState);
            return; // No-op, but show success
        }

        // Mode: destroy - break first then place
        // Vanilla behavior: destroy on air = just place (no break needed)
        boolean destroy = "destroy".equals(mode);
        boolean targetIsAir = before.equals("minecraft:air");
        boolean willBreak = destroy && !targetIsAir;

        // Validate: for destroy on air, only validate PLACE; otherwise validate BREAK then PLACE
        OperationKind operation = willBreak ? OperationKind.BREAK : OperationKind.PLACE;
        BlockMutation mutation = new BlockMutation(position, before, willBreak ? "minecraft:air" : blockState);

        // Validate first operation
        WorldAccess.ValidationResult validation = world.mayMutate(player.getUniqueId(), worldName, mutation, operation);
        if (!validation.allowed()) {
            handleValidationError(player, position, validation, before);
            return;
        }

        // For destroy mode (including destroy on air), also validate the place after break
        if (destroy) {
            BlockMutation placeMutation = new BlockMutation(position, willBreak ? "minecraft:air" : before, blockState);
            WorldAccess.ValidationResult placeValidation = world.mayMutate(player.getUniqueId(), worldName, placeMutation, OperationKind.PLACE);
            if (!placeValidation.allowed()) {
                handleValidationError(player, position, placeValidation, willBreak ? "minecraft:air" : before);
                return;
            }
        }

        // For replace mode, check if we need to break a solid block first
        long replaceBreakCount = 0;
        boolean needsBreak = false;
        if (!destroy && "replace".equals(mode)) {
            if (!PaperWorldAccess.isReplaceableOccupant(Bukkit.createBlockData(before).getMaterial())) {
                replaceBreakCount = 1;
                needsBreak = true;
            }
        }

        // Check tools/durability for break operations (only if actually breaking)
        if ((willBreak || needsBreak) && player.getGameMode() != GameMode.CREATIVE) {
            if (!BreakToolHelper.hasAnyMiningTool(player)) {
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool", Map.of("reason", "no_tool"));
                return;
            }
            Block target = player.getWorld().getBlockAt(position.x(), position.y(), position.z());
            if (!BreakToolHelper.canBreakBlock(player, target)) {
                sendProtocol(player, "error", "maxfastbuild.error.wrong_tool",
                        Map.of("block", target.getType().getKey().toString(), "reason", "no_effective_tool"));
                return;
            }
            long usable = estimateUsableToolHits(player);
            long needBreaks = (willBreak || needsBreak) ? 1 : 0;
            if (needBreaks > 0 && usable < needBreaks) {
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool_durability",
                        Map.of("reason", "durability", "need", needBreaks, "have", usable));
                return;
            }
        }

        // Check materials for place
        boolean requireMaterials = player.getGameMode() != GameMode.CREATIVE && !player.hasPermission("maxfastbuild.bypass.materials");
        boolean searchShulkers = getConfig().getBoolean("inventory.search-shulker-boxes", false);
        if (searchShulkers && getConfig().getBoolean("inventory.require-shulker-permission", false)
                && !player.hasPermission("maxfastbuild.material.shulker")) {
            searchShulkers = false;
        }
        String itemKey = PaperInventoryHelper.itemKeyFromBlockState(blockState);
        long need = 1;
        if (requireMaterials && !willBreak) {
            Material resolved = PaperInventoryHelper.resolveMaterial(itemKey);
            if (resolved == null || PaperWorldAccess.isForbiddenPlaceMaterial(resolved)) {
                sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", blockState));
                return;
            }
            long have = PaperInventoryHelper.count(player, itemKey, searchShulkers);
            if (have < need) {
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_materials",
                        Map.of("need", need, "have", have, "material", itemKey));
                return;
            }
        }

        // Calculate charge
        List<BlockMutation> mutations = List.of(new BlockMutation(position, before, blockState));
        Bounds bounds = new Bounds(position, position);
        BuildPlan plan = new BuildPlan(worldName, OperationKind.PLACE, bounds, mutations);
        BillingPolicy.Charge charge = billing().quote(plan, replaceBreakCount);

        boolean tookMoney = false;
        boolean tookItems = false;
        UUID taskId = UUID.randomUUID();
        String transactionId = taskId + ":withdraw";

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

        if (requireMaterials && !destroy) {
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
                "blocks", 1,
                "charge", charge.total().toPlainString()));
        messages.send(player, "setblock-success", formatPos(position), blockState);
    }

    private int parseCoordinate(String arg, int base) {
        if (arg.startsWith("~")) {
            String offset = arg.substring(1);
            int delta = offset.isEmpty() ? 0 : Integer.parseInt(offset);
            return base + delta;
        }
        return Integer.parseInt(arg);
    }

    private void handleValidationError(Player player, BlockPos position, WorldAccess.ValidationResult validation, String before) {
        String reason = validation.reason();
        if ("insufficient_tool".equals(reason)) {
            sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool", Map.of("reason", reason));
            return;
        }
        if ("unbreakable_block".equals(reason) || "unbreakable_replace".equals(reason)) {
            sendProtocol(player, "error", "maxfastbuild.error.unbreakable_block",
                    Map.of("position", position.toString(), "block", before, "reason", reason));
            return;
        }
        if ("forbidden_material".equals(reason)) {
            sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", before));
            return;
        }
        sendProtocol(player, "error", "maxfastbuild.error.protected", Map.of("position", position.toString(), "reason", reason));
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
        submit(player, selection, operation, null, false);
    }

    private void submit(Player player, Selection selection, OperationKind operation, Material filter, boolean keepOnly) {
        if (!player.hasPermission("maxfastbuild.use")) {
            sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
            return;
        }
        if (selection.first() == null || selection.second() == null) {
            sendProtocol(player, "error", "maxfastbuild.error.positions_required", Map.of());
            return;
        }
        String currentWorld = player.getWorld().getName();
        if (selection.world() == null || !selection.world().equals(currentWorld)) {
            sendProtocol(player, "error", "maxfastbuild.error.world_mismatch", Map.of("world", currentWorld));
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
        enqueueCommand(player, new QueuedCommand(selection, operation, filter, keepOnly));
    }

    private void enqueueCommand(Player player, QueuedCommand command) {
        int maxQueue = getConfig().getInt("execution.max-queued-commands-per-player", 32);
        Queue<QueuedCommand> queue = commandQueues.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        if (queue.size() >= maxQueue) {
            sendProtocol(player, "error", "maxfastbuild.error.queue_full", Map.of("limit", maxQueue));
            return;
        }
        queue.add(command);
        sendProtocol(player, "info", "maxfastbuild.task.queued", Map.of("position", queue.size()));
        tryDrainQueue(player);
    }

    private void tryDrainQueue(Player player) {
        UUID playerId = player.getUniqueId();
        if (pendingBuilds.containsKey(playerId)) return;
        if (executor.activeCount(playerId) >= getConfig().getInt("execution.max-concurrent-tasks-per-player", 2)) return;
        Queue<QueuedCommand> queue = commandQueues.get(playerId);
        if (queue == null || queue.isEmpty()) return;
        if (!rateLimit(player)) return;
        QueuedCommand next = queue.poll();
        if (next == null) return;
        int max = getConfig().getInt("execution.max-region-blocks", 100000);
        pendingBuilds.put(playerId, new PendingBuild(player, next.selection(), next.operation(), max, next.filter(), next.keepOnly()));
        sendProtocol(player, "info", "maxfastbuild.task.planning_started", Map.of("world", player.getWorld().getName()));
    }

    private void processCommandQueues() {
        if (!active) return;
        Iterator<Map.Entry<UUID, Queue<QueuedCommand>>> it = commandQueues.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Queue<QueuedCommand>> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            tryDrainQueue(player);
        }
    }

    /** Parse and submit an /mfbfill request (vanilla /fill compatible). */
    void handleFillCommand(Player player, String[] args) {
        if (!active || tasks == null) {
            if (messages != null) messages.send(player, "not-ready");
            return;
        }
        if (args.length < 7) {
            messages.send(player, "fill-usage");
            return;
        }
        Location loc = player.getLocation();
        BlockPos from;
        BlockPos to;
        try {
            from = new BlockPos(
                    parseCoordinate(args[0], loc.getBlockX()),
                    parseCoordinate(args[1], loc.getBlockY()),
                    parseCoordinate(args[2], loc.getBlockZ()));
            to = new BlockPos(
                    parseCoordinate(args[3], loc.getBlockX()),
                    parseCoordinate(args[4], loc.getBlockY()),
                    parseCoordinate(args[5], loc.getBlockZ()));
        } catch (NumberFormatException ex) {
            messages.send(player, "fill-invalid-pos", Arrays.toString(Arrays.copyOfRange(args, 0, 6)));
            return;
        }
        String blockState = args[6];
        if (!blockState.contains(":")) blockState = "minecraft:" + blockState;
        // Validate block state early to give a clean error.
        try {
            Bukkit.createBlockData(blockState);
        } catch (IllegalArgumentException ex) {
            messages.send(player, "fill-invalid-block", blockState);
            return;
        }

        Material filter = null;
        boolean keepOnly = false;
        boolean hollow = false;
        if (args.length > 7) {
            String mode = args[7].toLowerCase(Locale.ROOT);
            switch (mode) {
                case "destroy" -> { }
                case "hollow", "outline" -> hollow = true;
                case "keep" -> keepOnly = true;
                case "replace" -> {
                    if (args.length > 8) {
                        String filterKey = args[8];
                        if (!filterKey.contains(":")) filterKey = "minecraft:" + filterKey;
                        filter = Material.matchMaterial(filterKey, false);
                        if (filter == null) {
                            messages.send(player, "fill-invalid-filter", args[8]);
                            return;
                        }
                    }
                }
                default -> {
                    messages.send(player, "fill-invalid-mode", mode);
                    return;
                }
            }
        }

        Selection selection = new Selection(BuildMode.CUBE, from, to, hollow, blockState, player.getWorld().getName());
        submit(player, selection, OperationKind.PLACE, filter, keepOnly);
    }

    private void tickPlanners() {
        if (!active || pendingBuilds.isEmpty()) return;
        int batch = Math.max(1, getConfig().getInt("execution.planning.blocks-per-tick", 2000));
        int globalRemaining = globalBudgetCap(planningGlobalBudgetPerTick);
        Iterator<Map.Entry<UUID, PendingBuild>> it = pendingBuilds.entrySet().iterator();
        while (it.hasNext() && globalRemaining > 0) {
            Map.Entry<UUID, PendingBuild> entry = it.next();
            PendingBuild pending = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.getWorld().getName().equals(pending.selection.world())) {
                it.remove();
                continue;
            }
            if (pending.positions == null) {
                try {
                    pending.positions = new DefaultShapeGenerator().generate(
                            new ShapeRequest(pending.selection.mode(), pending.selection.first(), pending.selection.second(), pending.selection.hollow()), pending.maxBlocks);
                    pending.totalPositions = pending.positions.size();
                    pending.iterator = pending.positions.iterator();
                } catch (ShapeLimitException ex) {
                    sendProtocol(player, "error", "maxfastbuild.error.shape_too_large", Map.of("limit", pending.maxBlocks));
                    it.remove();
                    continue;
                } catch (RuntimeException ex) {
                    sendProtocol(player, "error", "maxfastbuild.error.protocol", Map.of("reason", ex.getMessage() == null ? "shape" : ex.getMessage()));
                    it.remove();
                    continue;
                }
            }
            int perPlayer = Math.min(batch, globalRemaining);
            int before = (int) pending.processed;
            PlanningError error = advancePlanning(pending, perPlayer);
            globalRemaining -= (int) (pending.processed - before);
            if (error != null) {
                sendProtocol(player, "error", error.key(), error.data());
                it.remove();
                continue;
            }
            if (!pending.iterator.hasNext()) {
                finalizePlanning(pending);
                it.remove();
            }
        }
    }

    private record PlanningError(String key, Map<String, ?> data) {}

    private PlanningError advancePlanning(PendingBuild pending, int batch) {
        PaperWorldAccess world = new PaperWorldAccess();
        Player player = pending.player;
        OperationKind operation = pending.operation;
        org.bukkit.World selectedWorld = Bukkit.getWorld(pending.selection.world());
        if (selectedWorld == null) {
            return new PlanningError("maxfastbuild.error.protected", Map.of("position", "unknown", "reason", "world_unloaded"));
        }
        String worldName = selectedWorld.getName();
        int minHeight = selectedWorld.getMinHeight();
        int maxHeight = selectedWorld.getMaxHeight();
        for (int i = 0; i < batch && pending.iterator.hasNext(); i++) {
            BlockPos pos = pending.iterator.next();
            pending.processed++;
            if (pos.y() < minHeight || pos.y() >= maxHeight) {
                return new PlanningError("maxfastbuild.error.protected", Map.of("position", pos.toString(), "reason", "unsafe_height"));
            }
            String before = world.stateAt(worldName, pos);
            if (operation == OperationKind.BREAK && before.equals("minecraft:air")) continue;
            if (pending.keepOnly && !before.equals("minecraft:air")) continue;
            if (pending.filter != null) {
                Material beforeMaterial = Bukkit.createBlockData(before).getMaterial();
                if (beforeMaterial != pending.filter) continue;
            }
            BlockMutation mutation = new BlockMutation(pos, before, operation == OperationKind.BREAK ? "minecraft:air" : pending.selection.material());
            WorldAccess.ValidationResult validation = world.mayMutate(player.getUniqueId(), worldName, mutation, operation);
            if (!validation.allowed()) {
                if ("insufficient_tool".equals(validation.reason())) {
                    return new PlanningError("maxfastbuild.error.insufficient_tool", Map.of("reason", validation.reason()));
                }
                if ("unbreakable_block".equals(validation.reason()) || "unbreakable_replace".equals(validation.reason())) {
                    return new PlanningError("maxfastbuild.error.unbreakable_block",
                            Map.of("position", pos.toString(), "block", before, "reason", validation.reason()));
                }
                if ("forbidden_material".equals(validation.reason())) {
                    return new PlanningError("maxfastbuild.error.invalid_material", Map.of("material", String.valueOf(pending.selection.material())));
                }
                return new PlanningError("maxfastbuild.error.protected", Map.of("position", pos.toString(), "reason", validation.reason()));
            }
            if (!before.equals(mutation.targetState())) {
                pending.mutations.add(mutation);
                if (operation == OperationKind.PLACE && PaperWorldAccess.requiresBreakToReplace(before)) {
                    pending.replaceBreakCount++;
                }
            }
        }
        return null;
    }

    private void finalizePlanning(PendingBuild pending) {
        Player player = pending.player;
        OperationKind operation = pending.operation;
        Selection selection = pending.selection;
        List<BlockMutation> mutations = pending.mutations;
        if (mutations.isEmpty()) {
            sendProtocol(player, "error", "maxfastbuild.error.no_changes", Map.of());
            return;
        }

        // Break mode, or place-over-solid: survival needs effective tools for every break target.
        boolean needsBreakTools = operation == OperationKind.BREAK
                || (operation == OperationKind.PLACE && pending.replaceBreakCount > 0);
        if (needsBreakTools && player.getGameMode() != GameMode.CREATIVE) {
            if (!BreakToolHelper.hasAnyMiningTool(player)) {
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool", Map.of("reason", "no_tool"));
                return;
            }
            Map<org.bukkit.Material, Boolean> canBreakCache = new HashMap<>();
            for (BlockMutation mutation : mutations) {
                if (operation == OperationKind.PLACE && !PaperWorldAccess.requiresBreakToReplace(mutation.expectedState())) {
                    continue;
                }
                org.bukkit.block.Block target = player.getWorld().getBlockAt(
                        mutation.position().x(), mutation.position().y(), mutation.position().z());
                org.bukkit.Material material = target.getType();
                Boolean cached = canBreakCache.get(material);
                if (cached == null) {
                    cached = BreakToolHelper.canBreakBlock(player, target);
                    canBreakCache.put(material, cached);
                }
                if (!cached) {
                    sendProtocol(player, "error", "maxfastbuild.error.wrong_tool",
                            Map.of("block", target.getType().getKey().toString(),
                                    "reason", "no_effective_tool"));
                    return;
                }
            }
            // Durability budget: each solid replace wears a tool once (MIN_REMAINING floor already in helper).
            if (operation == OperationKind.PLACE && pending.replaceBreakCount > 0) {
                long usable = estimateUsableToolHits(player);
                if (usable < pending.replaceBreakCount) {
                    sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool_durability",
                            Map.of("reason", "durability", "need", pending.replaceBreakCount, "have", usable));
                    return;
                }
            }
        }

        BuildPlan plan = new BuildPlan(selection.world(), operation,
                new Bounds(selection.first(), selection.second()), mutations);
        // Place-over-solid: charge per-block for place + for each required break.
        BillingPolicy.Charge charge = billing().quote(plan, operation == OperationKind.PLACE ? pending.replaceBreakCount : 0);
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

    /** Gzip magic bytes (0x1f 0x8b) — paste envelopes are gzipped JSON, legacy envelopes are plain JSON. */
    private static boolean isGzipPayload(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    /**
     * A verified envelope carrying a gzipped paste part. Each part is acknowledged immediately so the
     * client streams the next part; when the final part arrives the whole paste is planned and enqueued
     * as a single build task.
     */
    private void handlePastePayload(Player player, PasteTransfer.Payload payload) {
        if (!player.hasPermission("maxfastbuild.use")) {
            sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
            return;
        }
        Optional<PasteAccumulator.Assembled> assembled = pastes.accept(player.getUniqueId(), payload);
        sendMarked(player, GSON.toJson(Map.of("mfb", 1, "type", "paste_ack",
                "pasteSessionId", payload.pasteSessionId(), "part", payload.part(), "parts", payload.parts())));
        assembled.ifPresent(complete -> submitPaste(player, complete));
    }

    /**
     * Turn an assembled paste into a per-tick planning job. The block list is client-supplied, so every
     * mutation is re-validated against world state, protection, tool rules and materials exactly like the
     * shape-generated paths — this channel only skips shape generation.
     */
    private void submitPaste(Player player, PasteAccumulator.Assembled assembled) {
        String worldName = player.getWorld().getName();
        int maxBlocks = getConfig().getInt("execution.max-region-blocks", 100000);
        int[] origin = assembled.origin();
        List<String> palette = assembled.palette();
        // Defensive: strip any block-entity NBT ({...}) so states stay parseable. Block entities paste empty.
        List<String> normalized = new ArrayList<>(palette.size());
        for (String state : palette) {
            int brace = state.indexOf('{');
            normalized.add(brace >= 0 ? state.substring(0, brace) : state);
        }
        List<PastePos> positions = new ArrayList<>(assembled.entries().size());
        for (PasteTransfer.Entry entry : assembled.entries()) {
            if (entry.paletteIndex() >= normalized.size()) {
                sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "palette_index_out_of_range"));
                return;
            }
            String target = normalized.get(entry.paletteIndex());
            Material material;
            try {
                material = Bukkit.createBlockData(target).getMaterial();
            } catch (IllegalArgumentException ex) {
                sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", target));
                return;
            }
            if (material.isAir() || !material.isBlock() || RestrictedMaterials.isForbiddenPlace(material)) {
                continue;
            }
            positions.add(new PastePos(new BlockPos(origin[0] + entry.dx(), origin[1] + entry.dy(), origin[2] + entry.dz()), target));
        }
        if (positions.isEmpty()) {
            sendProtocol(player, "error", "maxfastbuild.error.no_changes", Map.of());
            return;
        }
        if (positions.size() > maxBlocks) {
            sendProtocol(player, "error", "maxfastbuild.error.shape_too_large", Map.of("limit", maxBlocks));
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
        if (executor.activeCount(player.getUniqueId()) >= getConfig().getInt("execution.max-concurrent-tasks-per-player", 2)) {
            sendProtocol(player, "error", "maxfastbuild.error.task_limit", Map.of());
            return;
        }
        if (pendingPastes.containsKey(player.getUniqueId())) {
            sendProtocol(player, "error", "maxfastbuild.error.protocol", Map.of("reason", "paste_in_progress"));
            return;
        }
        pendingPastes.put(player.getUniqueId(), new PendingPaste(player, worldName, positions));
    }

    private void tickPastePlanners() {
        if (!active || pendingPastes.isEmpty()) return;
        int batch = Math.max(1, getConfig().getInt("execution.planning.blocks-per-tick", 2000));
        int globalRemaining = globalBudgetCap(planningGlobalBudgetPerTick);
        Iterator<Map.Entry<UUID, PendingPaste>> it = pendingPastes.entrySet().iterator();
        while (it.hasNext() && globalRemaining > 0) {
            Map.Entry<UUID, PendingPaste> entry = it.next();
            PendingPaste pending = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.getWorld().getName().equals(pending.world)) {
                it.remove();
                continue;
            }
            int perPlayer = Math.min(batch, globalRemaining);
            int before = (int) pending.processed;
            PlanningError error = advancePastePlanning(pending, perPlayer);
            globalRemaining -= (int) (pending.processed - before);
            if (error != null) {
                sendProtocol(player, "error", error.key(), error.data());
                it.remove();
                continue;
            }
            if (!pending.iterator.hasNext()) {
                finalizePastePlanning(pending);
                it.remove();
            }
        }
    }

    private PlanningError advancePastePlanning(PendingPaste pending, int batch) {
        PaperWorldAccess world = new PaperWorldAccess();
        Player player = pending.player;
        World selectedWorld = Bukkit.getWorld(pending.world);
        if (selectedWorld == null) {
            return new PlanningError("maxfastbuild.error.protected", Map.of("position", "unknown", "reason", "world_unloaded"));
        }
        int minHeight = selectedWorld.getMinHeight();
        int maxHeight = selectedWorld.getMaxHeight();
        for (int i = 0; i < batch && pending.iterator.hasNext(); i++) {
            PastePos pp = pending.iterator.next();
            pending.processed++;
            BlockPos pos = pp.position();
            if (pos.y() < minHeight || pos.y() >= maxHeight) {
                return new PlanningError("maxfastbuild.error.protected", Map.of("position", pos.toString(), "reason", "unsafe_height"));
            }
            String before = world.stateAt(pending.world, pos);
            if (before.equals(pp.targetState())) continue;
            BlockMutation mutation = new BlockMutation(pos, before, pp.targetState());
            WorldAccess.ValidationResult validation = world.mayMutate(player.getUniqueId(), pending.world, mutation, OperationKind.PLACE);
            if (!validation.allowed()) {
                if ("insufficient_tool".equals(validation.reason())) {
                    return new PlanningError("maxfastbuild.error.insufficient_tool", Map.of("reason", validation.reason()));
                }
                if ("unbreakable_block".equals(validation.reason()) || "unbreakable_replace".equals(validation.reason())) {
                    return new PlanningError("maxfastbuild.error.unbreakable_block",
                            Map.of("position", pos.toString(), "block", before, "reason", validation.reason()));
                }
                if ("forbidden_material".equals(validation.reason())) {
                    return new PlanningError("maxfastbuild.error.invalid_material", Map.of("material", pp.targetState()));
                }
                return new PlanningError("maxfastbuild.error.protected", Map.of("position", pos.toString(), "reason", validation.reason()));
            }
            pending.mutations.add(mutation);
            if (PaperWorldAccess.requiresBreakToReplace(before)) {
                pending.replaceBreakCount++;
            }
        }
        return null;
    }

    private void finalizePastePlanning(PendingPaste pending) {
        Player player = pending.player;
        List<BlockMutation> mutations = pending.mutations;
        if (mutations.isEmpty()) {
            sendProtocol(player, "error", "maxfastbuild.error.no_changes", Map.of());
            return;
        }

        // Place-over-solid survival paste needs effective tools for every solid replace target.
        if (pending.replaceBreakCount > 0 && player.getGameMode() != GameMode.CREATIVE) {
            if (!BreakToolHelper.hasAnyMiningTool(player)) {
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool", Map.of("reason", "no_tool"));
                return;
            }
            Map<Material, Boolean> canBreakCache = new HashMap<>();
            for (BlockMutation mutation : mutations) {
                if (!PaperWorldAccess.requiresBreakToReplace(mutation.expectedState())) continue;
                Block target = player.getWorld().getBlockAt(mutation.position().x(), mutation.position().y(), mutation.position().z());
                Material material = target.getType();
                Boolean cached = canBreakCache.get(material);
                if (cached == null) {
                    cached = BreakToolHelper.canBreakBlock(player, target);
                    canBreakCache.put(material, cached);
                }
                if (!cached) {
                    sendProtocol(player, "error", "maxfastbuild.error.wrong_tool",
                            Map.of("block", target.getType().getKey().toString(), "reason", "no_effective_tool"));
                    return;
                }
            }
            long usable = estimateUsableToolHits(player);
            if (usable < pending.replaceBreakCount) {
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool_durability",
                        Map.of("reason", "durability", "need", pending.replaceBreakCount, "have", usable));
                return;
            }
        }

        BlockPos first = mutations.getFirst().position();
        BlockPos min = first;
        BlockPos max = first;
        for (BlockMutation mutation : mutations) {
            BlockPos pos = mutation.position();
            min = new BlockPos(Math.min(min.x(), pos.x()), Math.min(min.y(), pos.y()), Math.min(min.z(), pos.z()));
            max = new BlockPos(Math.max(max.x(), pos.x()), Math.max(max.y(), pos.y()), Math.max(max.z(), pos.z()));
        }
        BuildPlan plan = new BuildPlan(pending.world, OperationKind.PLACE, new Bounds(min, max), mutations);
        BillingPolicy.Charge charge = billing().quote(plan, pending.replaceBreakCount);
        boolean requireMaterials = player.getGameMode() != GameMode.CREATIVE
                && !player.hasPermission("maxfastbuild.bypass.materials");
        boolean searchShulkers = getConfig().getBoolean("inventory.search-shulker-boxes", false);
        if (searchShulkers && getConfig().getBoolean("inventory.require-shulker-permission", false)
                && !player.hasPermission("maxfastbuild.material.shulker")) {
            searchShulkers = false;
        }

        // A paste uses many block types: count and deduct per unique material.
        Map<String, Long> needByMaterial = new LinkedHashMap<>();
        if (requireMaterials) {
            for (BlockMutation mutation : mutations) {
                needByMaterial.merge(PaperInventoryHelper.itemKeyFromBlockState(mutation.targetState()), 1L, Long::sum);
            }
            for (Map.Entry<String, Long> entry : needByMaterial.entrySet()) {
                Material resolved = PaperInventoryHelper.resolveMaterial(entry.getKey());
                if (resolved == null || PaperWorldAccess.isForbiddenPlaceMaterial(resolved)) {
                    sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", entry.getKey()));
                    return;
                }
                long have = PaperInventoryHelper.count(player, entry.getKey(), searchShulkers);
                if (have < entry.getValue()) {
                    sendProtocol(player, "error", "maxfastbuild.error.insufficient_materials",
                            Map.of("need", entry.getValue(), "have", have, "material", entry.getKey()));
                    return;
                }
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
        Map<String, Long> takenByMaterial = new LinkedHashMap<>();
        if (requireMaterials) {
            for (Map.Entry<String, Long> entry : needByMaterial.entrySet()) {
                long removed = PaperInventoryHelper.take(player, entry.getKey(), entry.getValue(), searchShulkers);
                if (removed < entry.getValue()) {
                    for (Map.Entry<String, Long> taken : takenByMaterial.entrySet()) {
                        PaperInventoryHelper.giveOrDrop(player, taken.getKey(), taken.getValue());
                    }
                    if (tookMoney) refundMoney(player, taskId, charge.total(), transactionId);
                    sendProtocol(player, "error", "maxfastbuild.error.insufficient_materials",
                            Map.of("need", entry.getValue(), "have", removed, "material", entry.getKey()));
                    return;
                }
                takenByMaterial.put(entry.getKey(), removed);
            }
            tookItems = true;
        }

        Instant now = Instant.now();
        BuildTask task = new BuildTask(taskId, player.getUniqueId(), player.getName(), plan, TaskStatus.QUEUED,
                0, 0, null, charge.total(), BigDecimal.ZERO, now, now, null);
        try {
            executor.enqueue(task);
        } catch (RuntimeException ex) {
            if (tookItems) {
                for (Map.Entry<String, Long> taken : takenByMaterial.entrySet()) {
                    PaperInventoryHelper.giveOrDrop(player, taken.getKey(), taken.getValue());
                }
            }
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
        int globalRemaining = globalBudgetCap(globalBudgetPerTick);
        for (UUID id : executor.activeIds()) {
            if (globalRemaining <= 0) break;
            try {
                int perTask = Math.min(count, globalRemaining);
                TaskExecutor.TickResult result = executor.tick(id, perTask);
                globalRemaining -= result.changed() + result.skipped();
                if (result.finished()) settlePartial(result);
            } catch (RuntimeException ex) {
                getLogger().severe("Task " + id + " failed: " + ex.getMessage());
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
            mergeConfigDefaults();
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
        if (pendingBuilds.remove(player.getUniqueId()) != null) {
            cancelled++;
        }
        if (pendingPastes.remove(player.getUniqueId()) != null) {
            cancelled++;
        }
        if (commandQueues.remove(player.getUniqueId()) != null) {
            cancelled++;
        }
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
                "expiresAt", session.expiresAt().toString(),
                "maxBlocks", getConfig().getInt("execution.max-region-blocks", 100000))));
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

    /** 0 in config = unlimited (Integer.MAX_VALUE); otherwise the configured value. */
    private static int globalBudgetCap(int budget) {
        return budget <= 0 ? Integer.MAX_VALUE : budget;
    }

    private record ClientRequest(String operation, String mode, BlockPos first, BlockPos second, boolean hollow, String material) {}
    private record QueuedCommand(Selection selection, OperationKind operation, Material filter, boolean keepOnly) {}
    private record Selection(BuildMode mode, BlockPos first, BlockPos second, boolean hollow, String material, String world) {
        Selection withMode(BuildMode value) { return new Selection(value, first, second, hollow, material, world); }
        Selection withFirst(BlockPos value) { return new Selection(mode, value, second, hollow, material, world); }
        Selection withSecond(BlockPos value) { return new Selection(mode, first, value, hollow, material, world); }
        Selection withHollow(boolean value) { return new Selection(mode, first, second, value, material, world); }
        Selection withMaterial(String value) { return new Selection(mode, first, second, hollow, value, world); }
        Selection withWorld(String value) { return new Selection(mode, first, second, hollow, material, value); }
    }

    /** In-progress region planning for a player. Planning runs over multiple server ticks to avoid freezing the main thread. */
    private static final class PendingBuild {        final Player player;
        final OperationKind operation;
        final Selection selection;
        final int maxBlocks;
        final long startedAt;
        final Material filter;
        final boolean keepOnly;
        final List<BlockMutation> mutations = new ArrayList<>();
        long replaceBreakCount = 0;
        long totalPositions = -1;
        long processed = 0;
        boolean failed = false;
        String failureReason = null;
        Set<BlockPos> positions;
        Iterator<BlockPos> iterator;
        boolean notified = false;

        PendingBuild(Player player, Selection selection, OperationKind operation, int maxBlocks) {
            this(player, selection, operation, maxBlocks, null, false);
        }

        PendingBuild(Player player, Selection selection, OperationKind operation, int maxBlocks, Material filter, boolean keepOnly) {
            this.player = player;
            this.selection = selection;
            this.operation = operation;
            this.maxBlocks = maxBlocks;
            this.filter = filter;
            this.keepOnly = keepOnly;
            this.startedAt = System.currentTimeMillis();
        }
    }

    /** Absolute position and its palette target state (client-supplied, re-validated per tick). */
    private record PastePos(BlockPos position, String targetState) {}

    /** In-progress validation of an assembled paste, ticked like a {@link PendingBuild}. */
    private static final class PendingPaste {
        final Player player;
        final String world;
        final Iterator<PastePos> iterator;
        final List<BlockMutation> mutations = new ArrayList<>();
        long replaceBreakCount = 0;
        long processed = 0;

        PendingPaste(Player player, String world, List<PastePos> positions) {
            this.player = player;
            this.world = world;
            this.iterator = positions.iterator();
        }
    }
}
