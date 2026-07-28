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
    /** Guards handlers and ticks during PlugMan unload / failed enable. */
    private volatile boolean active;

    @Override public void onEnable() {
        active = false;
        saveDefaultConfig();
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
        } catch (RuntimeException ex) {
            getLogger().severe("MaxFastBuild failed to enable: " + ex.getMessage());
            shutdownResources(false);
            throw ex;
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
                    if (!player.hasPermission("maxfastbuild.use") || !player.hasPermission("maxfastbuild.break")) {
                        sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.break"));
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
                    if ("break".equalsIgnoreCase(request.operation())) {
                        if (!player.hasPermission("maxfastbuild.use") || !player.hasPermission("maxfastbuild.break")) {
                            sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.break"));
                            return;
                        }
                    } else if (!player.hasPermission("maxfastbuild.use")) {
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
        if (!active || tasks == null) {
            player.sendMessage(Component.text("MaxFastBuild is not ready"));
            return;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("/mfb mode|pos1|pos2|apply|cancel|status|hollow|material"));
            return;
        }
        Selection selection = selections.computeIfAbsent(player.getUniqueId(), ignored -> new Selection(BuildMode.LINE, null, null, false, "minecraft:stone"));
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "mode" -> {
                if (args.length < 2) return;
                selections.put(player.getUniqueId(), selection.withMode(BuildMode.valueOf(args[1].toUpperCase(Locale.ROOT))));
                player.sendMessage(Component.text("Mode: " + args[1]));
            }
            case "pos1" -> { selections.put(player.getUniqueId(), selection.withFirst(at(player))); player.sendMessage(Component.text("Position 1 set")); }
            case "pos2" -> { selections.put(player.getUniqueId(), selection.withSecond(at(player))); player.sendMessage(Component.text("Position 2 set")); }
            case "hollow" -> selections.put(player.getUniqueId(), selection.withHollow(args.length < 2 || Boolean.parseBoolean(args[1])));
            case "material" -> { if (args.length > 1) selections.put(player.getUniqueId(), selection.withMaterial(args[1])); }
            case "apply" -> submit(player, selection, OperationKind.PLACE);
            case "status" -> player.sendMessage(Component.text("Active tasks: " + tasks.activeCount(player.getUniqueId())));
            case "cancel" -> cancelPlayerTasks(player);
            default -> player.sendMessage(Component.text("Unknown subcommand. Use /mfb mode|pos1|pos2|apply|cancel|status|hollow|material"));
        }
    }

    private void handleClientRequest(Player player, ClientRequest request) {
        BuildMode mode = BuildMode.valueOf(request.mode().toUpperCase(Locale.ROOT));
        Selection selection = new Selection(mode, request.first(), request.second(), request.hollow(), request.material());
        selections.put(player.getUniqueId(), selection);
        submit(player, selection, OperationKind.valueOf(request.operation().toUpperCase(Locale.ROOT)));
    }

    private void submit(Player player, Selection selection, OperationKind operation) {
        if (!player.hasPermission("maxfastbuild.use")) {
            sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
            return;
        }
        if (operation == OperationKind.BREAK && !player.hasPermission("maxfastbuild.break")) {
            sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.break"));
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
                if ("forbidden_material".equals(validation.reason())) {
                    sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", String.valueOf(selection.material())));
                    return;
                }
                sendProtocol(player, "error", "maxfastbuild.error.protected", Map.of("position", pos.toString(), "reason", validation.reason()));
                return;
            }
            if (!before.equals(mutation.targetState())) mutations.add(mutation);
        }
        if (mutations.isEmpty()) { sendProtocol(player, "error", "maxfastbuild.error.no_changes", Map.of()); return; }
        if (operation == OperationKind.BREAK && player.getGameMode() != GameMode.CREATIVE) {
            if (!BreakToolHelper.hasAnyMiningTool(player)) {
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool", Map.of("reason", "no_tool"));
                return;
            }
            for (BlockMutation mutation : mutations) {
                org.bukkit.block.Block target = player.getWorld().getBlockAt(
                        mutation.position().x(), mutation.position().y(), mutation.position().z());
                if (!BreakToolHelper.canBreakBlock(player, target)) {
                    sendProtocol(player, "error", "maxfastbuild.error.wrong_tool",
                            Map.of("block", target.getType().getKey().toString(),
                                    "reason", "no_effective_tool"));
                    return;
                }
            }
        }

        BuildPlan plan = new BuildPlan(player.getWorld().getName(), operation, new Bounds(selection.first(), selection.second()), mutations);
        BillingPolicy.Charge charge = billing().quote(plan);
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
        BigDecimal areaPart = policy.perAreaEnabled()
                ? policy.perArea().multiply(BigDecimal.valueOf(task.plan().bounds().maximumPlaneArea()))
                : BigDecimal.ZERO;
        BigDecimal blockPart = policy.perBlockEnabled()
                ? policy.perBlock().multiply(BigDecimal.valueOf(planned))
                : BigDecimal.ZERO;
        BigDecimal operationPart = BigDecimal.ZERO;
        if (appliedCount == 0 && policy.perOperationEnabled()) {
            operationPart = policy.perOperation();
        }
        BigDecimal refund = policy.refund(
                new BillingPolicy.Charge(operationPart, areaPart, blockPart, task.charged()),
                planned, appliedCount);
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
        if (!active || tasks == null || ledger == null) {
            sender.sendMessage("MaxFastBuild is not ready");
            return true;
        }
        if (!sender.hasPermission("maxfastbuild.admin")) return true;
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("MaxFastBuild configuration reloaded");
        } else if (args.length > 0 && args[0].equalsIgnoreCase("recovery")) {
            sender.sendMessage("Recoverable tasks: " + tasks.recoverable().size() + ", pending ledger entries: " + ledger.pending().size());
        } else {
            sender.sendMessage("/mfbadmin reload|recovery");
        }
        return true;
    }

    private void cancelPlayerTasks(Player player) {
        if (!active || tasks == null || executor == null) return;
        for (BuildTask task : tasks.recoverable()) {
            if (!task.playerId().equals(player.getUniqueId())) continue;
            if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.RUNNING
                    && task.status() != TaskStatus.PAUSED_OFFLINE && task.status() != TaskStatus.PAUSED_SHUTDOWN) continue;
            try {
                TaskExecutor.TickResult aborted = executor.abort(task.id());
                settlePartial(aborted);
            } catch (RuntimeException ex) {
                try {
                    BuildTask cancelling = task.transition(TaskStatus.CANCELLING, Instant.now());
                    tasks.save(cancelling);
                    int applied = task.appliedCount();
                    settlePartial(new TaskExecutor.TickResult(
                            cancelling.transition(TaskStatus.CANCELLED, Instant.now()), 0, 0, applied, true));
                } catch (RuntimeException inner) {
                    getLogger().warning("Cancel settle failed for " + task.id() + ": " + inner.getMessage());
                }
            }
        }
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

    private void sendProtocol(Player player, String type, String key, Map<String, ?> data) {
        sendMarked(player, GSON.toJson(Map.of("mfb", 1, "type", type, "messageKey", key, "data", data)));
    }

    private void sendMarked(Player player, String json) { player.sendMessage(Component.text(ProtocolEnvelope.MESSAGE_MARKER + json)); }
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
