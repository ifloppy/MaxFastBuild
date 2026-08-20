package dev.maxfastbuild.paper;

import com.google.gson.Gson;
import dev.maxfastbuild.api.*;
import dev.maxfastbuild.core.billing.BillingPolicy;
import dev.maxfastbuild.core.limit.RequestLimitValidator;
import dev.maxfastbuild.core.limit.ServerLimits;
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
    private static final Material PREVIEW_MATERIAL = Material.GREEN_STAINED_GLASS;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    /** Block positions currently replaced by client-only preview packets. */
    private final Map<UUID, PreviewState> previews = new ConcurrentHashMap<>();
    private final Map<UUID, TokenBucket> limits = new ConcurrentHashMap<>();
    private final Map<UUID, SecureProtocol.Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingBuild> pendingBuilds = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPaste> pendingPastes = new ConcurrentHashMap<>();
    /** Last computed paste materials per player, kept even after the pending is removed (materials check may reject). */
    private final Map<UUID, PasteMaterials> lastPasteNeeds = new ConcurrentHashMap<>();
    private final Map<UUID, List<PendingEntity>> taskEntities = new ConcurrentHashMap<>();
    private final Map<UUID, PaperInventoryHelper.RemovalLedger> taskRemovals = new ConcurrentHashMap<>();
    private final Map<UUID, Queue<QueuedCommand>> commandQueues = new ConcurrentHashMap<>();
    private final CommandChunkAssembler chunks = new CommandChunkAssembler(Clock.systemUTC(), Duration.ofSeconds(15));
    /** Reassembles multi-part Litematica paste payloads (session per player+id, 120s window). */
    private PasteAccumulator pastes;
    /** Effective server limits, replaced atomically on reload. */
    private volatile ServerLimits serverLimits;
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
        serverLimits = loadServerLimits();
        pastes = new PasteAccumulator(Clock.systemUTC(), Duration.ofSeconds(120),
                serverLimits.maxPasteParts(), serverLimits.maxBlocksPerPart(), serverLimits.maxPasteTotalBlocks());
        refreshDebugFlags();
        SeedCatalog.reload(getConfig());
        messages = new PluginMessages(this);
        messages.reload();
        try {
            protocol = new SecureProtocol(Clock.systemUTC(), Duration.ofMinutes(getConfig().getLong("protocol.session-minutes", 30)), serverLimits.maxPayloadBytes());
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
            executor = new TaskExecutor(tasks, new PaperWorldAccess(), audit, Clock.systemUTC(), saveInterval,
                    getConfig().getBoolean("paste.replace-mismatched", true));
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

    /** Apply config switches that gate internal diagnostics (no runtime allocation). */
    private void refreshDebugFlags() {
        boolean readback = debugEnabled() && getConfig().getBoolean("debug.tile-readback", false);
        PaperNbtHelper.setTileReadbackEnabled(readback);
        getLogger().info("tile-readback diagnostics " + (readback ? "ENABLED" : "disabled"));
    }

    /** Master switch for all MaxFastBuild diagnostic logging ({@code debug.enabled}). */
    private boolean debugEnabled() {
        return getConfig().getBoolean("debug.enabled", false);
    }

    /** Debug-only server log line (suppressed unless {@code debug.enabled}). */
    private void debugLog(String message) {
        if (debugEnabled()) getLogger().info("[MaxFastBuild] " + message);
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
            clearAllPreviews();
            selections.clear();
            limits.clear();
            sessions.clear();
            pendingBuilds.clear();
            pendingPastes.clear();
            lastPasteNeeds.clear();
            commandQueues.clear();
            try { chunks.clear(); } catch (RuntimeException ignored) { }
            try { if (pastes != null) pastes.clear(); } catch (RuntimeException ignored) { }
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
                case "hello" -> {
                    if (parts.length >= 3) {
                        int clientVersion;
                        try {
                            clientVersion = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ignored) {
                            sendProtocol(player, "error", "maxfastbuild.error.version_mismatch",
                                    Map.of("serverVersion", String.valueOf(ProtocolEnvelope.CURRENT_VERSION),
                                            "clientVersion", parts.length >= 3 ? parts[2] : "?"));
                            return;
                        }
                        if (clientVersion != ProtocolEnvelope.CURRENT_VERSION) {
                            sendProtocol(player, "error", "maxfastbuild.error.version_mismatch",
                                    Map.of("serverVersion", String.valueOf(ProtocolEnvelope.CURRENT_VERSION),
                                            "clientVersion", String.valueOf(clientVersion)));
                            return;
                        }
                    }
                    issueSession(player);
                }
                case "place" -> {
                    if (!player.hasPermission("maxfastbuild.use")) {
                        sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
                        return;
                    }
                    CompactPlaceCommand.Intent intent = CompactPlaceCommand.parse(message);
                    handleClientRequest(player, new ClientRequest("place", intent.mode().name().toLowerCase(Locale.ROOT),
                            intent.first(), intent.second(), intent.third(), intent.hollow(),
                            intent.spacingX(), intent.spacingY(), intent.spacingZ(), intent.material()));
                }
                case "break" -> {
                    if (!player.hasPermission("maxfastbuild.use")) {
                        sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
                        return;
                    }
                    CompactBreakCommand.Intent intent = CompactBreakCommand.parse(message);
                    handleClientRequest(player, new ClientRequest("break", intent.mode().name().toLowerCase(Locale.ROOT),
                            intent.first(), intent.second(), intent.third(), intent.hollow(),
                            intent.spacingX(), intent.spacingY(), intent.spacingZ(), "minecraft:air"));
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
        clearSelectionPreview(event.getPlayer());
        if (!active || tasks == null || executor == null) return;
        UUID playerId = event.getPlayer().getUniqueId();
        pendingBuilds.remove(playerId);
        commandQueues.remove(playerId);
        pendingPastes.remove(playerId);
        lastPasteNeeds.remove(playerId);
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

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        // A block-change packet only belongs to the old world. Drop it before the next
        // selection command so an old preview can never be mistaken for a new one.
        clearSelectionPreview(event.getPlayer());
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!active || tasks == null || executor == null) return;
        for (BuildTask task : tasks.recoverable()) {
            if (!task.playerId().equals(event.getPlayer().getUniqueId())) continue;
            if (task.status() != TaskStatus.PAUSED_OFFLINE && task.status() != TaskStatus.PAUSED_SHUTDOWN
                    && task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.QUEUED) continue;
            if (executor.isActive(task.id())) continue;
            BuildTask queued = task.status() == TaskStatus.QUEUED
                    ? task
                    : task.transition(TaskStatus.QUEUED, Instant.now());
            executor.enqueue(queued);
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
                ignored -> new Selection(BuildMode.LINE, null, null, 0, "minecraft:stone", player.getWorld().getName()));
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "mode" -> {
                if (args.length < 2) {
                    messages.send(player, "mode-usage");
                    sendModeHelp(player);
                    return;
                }
                BuildMode mode;
                try {
                    mode = BuildMode.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    messages.send(player, "mode-invalid", args[1], modeListLocalized());
                    sendModeHelp(player);
                    return;
                }
                Selection next = selection.withMode(mode);
                selections.put(player.getUniqueId(), next);
                refreshSelectionPreview(player, next);
                messages.send(player, "mode-set", modeDisplayName(mode), modeEffect(mode));
            }
            case "pos1" -> {
                BlockPos pos = commandPosition(player, args);
                if (pos == null) return;
                Selection next = selection.withFirst(pos).withWorld(player.getWorld().getName());
                selections.put(player.getUniqueId(), next);
                refreshSelectionPreview(player, next);
                messages.send(player, "pos1-set", formatPos(pos));
            }
            case "pos2" -> {
                BlockPos pos = commandPosition(player, args);
                if (pos == null) return;
                Selection next = selection.withSecond(pos).withWorld(player.getWorld().getName());
                selections.put(player.getUniqueId(), next);
                refreshSelectionPreview(player, next);
                messages.send(player, "pos2-set", formatPos(pos));
            }
            case "pos3" -> {
                BlockPos pos = commandPosition(player, args);
                if (pos == null) return;
                Selection next = selection.withThird(pos).withWorld(player.getWorld().getName());
                selections.put(player.getUniqueId(), next);
                refreshSelectionPreview(player, next);
                messages.send(player, "pos3-set", formatPos(pos));
            }
            case "array-spacing" -> {
                if (args.length < 4) {
                    messages.send(player, "array-spacing-usage");
                    return;
                }
                try {
                    int x = parseArraySpacing(args[1]);
                    int y = parseArraySpacing(args[2]);
                    int z = parseArraySpacing(args[3]);
                    Selection next = selection.withArraySpacing(x, y, z);
                    selections.put(player.getUniqueId(), next);
                    refreshSelectionPreview(player, next);
                    messages.send(player, "array-spacing-set", x, y, z);
                } catch (NumberFormatException ex) {
                    messages.send(player, "array-spacing-invalid", String.join(" ", args[1], args[2], args[3]));
                }
            }
            case "hollow" -> {
                int hollow;
                if (args.length < 2) {
                    boolean old = selection.hollow() != 0;
                    hollow = old ? 0 : 1;
                } else {
                    try {
                        hollow = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        hollow = "true".equalsIgnoreCase(args[1]) || "1".equals(args[1]) || "on".equalsIgnoreCase(args[1]) ? 1 : 0;
                    }
                }
                Selection next = selection.withHollow(hollow);
                selections.put(player.getUniqueId(), next);
                refreshSelectionPreview(player, next);
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
            case "replace" -> handleReplaceCommand(player, selection, args);
            case "setblock" -> submitSetBlock(player, args);
            case "status" -> {
                Selection current = selections.getOrDefault(player.getUniqueId(), selection);
                String missing = plain(messages.raw("status-pos-missing"));
                String p1 = current.first() == null ? missing : formatPos(current.first());
                String p2 = current.second() == null ? missing : formatPos(current.second());
                String p3 = current.third() == null ? missing : formatPos(current.third());
                messages.send(player, "status-selection",
                        modeDisplayName(current.mode()),
                        current.hollow(),
                        current.material());
                messages.send(player, "status-pos", p1, p2, p3);
                messages.send(player, "status-tasks", executor.activeCount(player.getUniqueId()));
                messages.send(player, "status-queue", queueSize(player.getUniqueId()));
            }
            case "cancel" -> clearPendingQueue(player);
            case "clearpos" -> clearSelectionPoints(player);
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
        messages.send(player, "help-line-pos3");
        messages.send(player, "help-line-array-spacing");
        messages.send(player, "help-line-material");
        messages.send(player, "help-line-hollow");
        messages.send(player, "help-line-apply");
        messages.send(player, "help-line-replace");
        messages.send(player, "help-line-status");
        messages.send(player, "help-line-cancel");
        messages.send(player, "help-line-clearpos");
        messages.send(player, "help-line-about");
        messages.send(player, "help-line-setblock");
        messages.send(player, "help-modes");
        sendModeHelp(player);
        messages.send(player, "help-line-preview");
        messages.send(player, "help-tip");
    }

    private void sendModeHelp(Player player) {
        messages.send(player, "mode-list-header");
        for (BuildMode mode : BuildMode.values()) {
            String id = mode.name().toLowerCase(Locale.ROOT);
            messages.send(player, "mode-list-entry", id, modeDisplayName(mode), modeEffect(mode));
        }
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
        String clientMaterial = request.material() == null ? "minecraft:stone" : request.material();
        String clientProperties = extractBlockProperties(clientMaterial);
        String baseMaterial = stripBlockProperties(clientMaterial);
        Selection anchors = new Selection(mode, request.first(), request.second(), request.third(), request.hollow(),
                request.spacingX(), request.spacingY(), request.spacingZ(), baseMaterial, player.getWorld().getName());
        submitFromHand(player, anchors, false, clientProperties);
    }

    /** /mfb apply and /__mfb — resolve place/break from main hand. */
    private void submitFromHand(Player player, Selection selection) {
        submitFromHand(player, selection, true, null);
    }

    private void submitFromHand(Player player, Selection selection, boolean cliFeedback) {
        submitFromHand(player, selection, cliFeedback, null);
    }

    private void submitFromHand(Player player, Selection selection, boolean cliFeedback, String clientProperties) {
        HandIntent intent = HandIntent.from(player);
        if (intent.isNone()) {
            if (cliFeedback && messages != null) messages.send(player, "apply-need-hand");
            notifyPlayer(player, "error", "maxfastbuild.error.hold_block_or_tool",
                    Map.of("reason", intent.rejectReason() == null ? "none" : intent.rejectReason()));
            return;
        }
        Selection effective = selection;
        if (intent.isPlace()) {
            String material = intent.material();
            if (clientProperties != null && !clientProperties.isEmpty()) {
                material = material + clientProperties;
            }
            effective = selection.withMaterial(material);
            selections.put(player.getUniqueId(), effective);
            if (cliFeedback && messages != null) {
                messages.send(player, "apply-place-from-hand", material);
            }
        } else {
            selections.put(player.getUniqueId(), effective);
            if (cliFeedback && messages != null) {
                messages.send(player, "apply-break-from-hand");
            }
        }
        submit(player, effective, intent.operation());
    }

    private static String extractBlockProperties(String material) {
        int bracket = material.indexOf('[');
        if (bracket < 0) return "";
        return material.substring(bracket);
    }

    private static String stripBlockProperties(String material) {
        int bracket = material.indexOf('[');
        if (bracket < 0) return material;
        return material.substring(0, bracket);
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
        PaperInventoryHelper.SearchOptions search = inventorySearch(player, searchShulkers);
        List<PaperInventoryHelper.ItemSource> singleSources = search.sources(player);
        String itemKey = PaperInventoryHelper.itemKeyFromBlockState(blockState);
        long need = 1;
        if (requireMaterials && !willBreak) {
            Material resolved = PaperInventoryHelper.resolveMaterial(itemKey);
            if (resolved == null || PaperWorldAccess.isForbiddenPlaceMaterial(resolved)) {
                sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", blockState));
                return;
            }
            long have = PaperInventoryHelper.count(singleSources, itemKey,
                    search.requiredBuckets, search.fireRequiresFlint);
            if (have < need) {
                sendMaterialError(player, itemKey, need, have, true, fluidBucketRequirement());
                return;
            }
        }

        // Calculate charge
        List<BlockMutation> mutations = List.of(new BlockMutation(position, before, blockState));
        Bounds bounds = new Bounds(position, position);
        BuildPlan plan = new BuildPlan(worldName, OperationKind.PLACE, bounds, mutations);
        BillingPolicy.Charge charge = billing().quote(plan, replaceBreakCount);

        boolean tookMoney = false;
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

        PaperInventoryHelper.RemovalLedger singleRemovals = null;
        if (requireMaterials && !destroy) {
            singleRemovals = new PaperInventoryHelper.RemovalLedger();
            auditContainerTakes(player, worldName, true, singleSources, null);
            long removed = PaperInventoryHelper.take(singleSources, itemKey, need,
                    search.requiredBuckets, search.fireRequiresFlint, singleRemovals);
            if (removed < need) {
                auditContainerRefunds(player.getUniqueId(), player.getName(), worldName, singleRemovals);
                singleRemovals.restoreAll();
                if (tookMoney) refundMoney(player, taskId, charge.total(), transactionId);
                sendMaterialError(player, itemKey, need, removed,
                        search.fireRequiresFlint, search.requiredBuckets);
                return;
            }
        }

        Instant now = Instant.now();
        BuildTask task = new BuildTask(taskId, player.getUniqueId(), player.getName(), plan, TaskStatus.QUEUED,
                0, 0, Set.of(), null, tookMoney ? charge.total() : BigDecimal.ZERO, BigDecimal.ZERO, now, now, null);
        try {
            executor.enqueue(task);
        } catch (RuntimeException ex) {
            if (singleRemovals != null) {
                auditContainerRefunds(player.getUniqueId(), player.getName(), worldName, singleRemovals);
                singleRemovals.restoreAll();
            }
            compensate(player, taskId, tookMoney ? charge.total() : BigDecimal.ZERO, transactionId, ex);
            return;
        }
        if (singleRemovals != null) taskRemovals.put(taskId, singleRemovals);

        sendProtocol(player, "accepted", "maxfastbuild.task.accepted", Map.of(
                "taskId", taskId.toString(),
                "blocks", 1,
                "affectedBlocks", 1,
                "regionBlocks", 1,
                "sizeX", 1,
                "sizeY", 1,
                "sizeZ", 1,
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

    private static int parseArraySpacing(String raw) {
        int value = Integer.parseInt(raw);
        if (value < 1 || value > 64) throw new NumberFormatException("array_spacing");
        return value;
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

    private String modeEffect(BuildMode mode) {
        String raw = messages == null ? null : messages.raw("mode-effect-" + mode.name().toLowerCase(Locale.ROOT));
        return raw == null || raw.isBlank() ? "-" : plain(raw);
    }

    /**
     * Send a client-only green-glass preview. The generated positions deliberately use the same
     * request object as the executor, so /mfb apply cannot drift from what the player saw.
     */
    private void refreshSelectionPreview(Player player, Selection selection) {
        clearSelectionPreview(player);
        if (selection == null || selection.first() == null) return;

        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        positions.add(selection.first());
        if (selection.second() != null) {
            if (selection.mode() == BuildMode.ARC && selection.third() == null) {
                // The middle point is not enough to generate an arc, but both selected anchors
                // are still useful feedback while the player is choosing the third point.
                positions.add(selection.second());
            } else {
                ShapeRequest request = shapeRequest(selection);
                Optional<RequestLimitValidator.Violation> violation = RequestLimitValidator.region(
                        request.bounds(), limits());
                if (violation.isPresent()) {
                    sendLimitError(player, violation.get());
                    return;
                }
                try {
                    positions = new LinkedHashSet<>(new DefaultShapeGenerator().generate(request,
                            shapeGenerationLimit(limits().maxRegionBlocks())));
                } catch (ShapeLimitException ex) {
                    messages.send(player, "preview-too-large", limits().maxRegionBlocks());
                    return;
                } catch (RuntimeException ex) {
                    messages.send(player, "preview-unavailable");
                    return;
                }
            }
        }

        World world = player.getWorld();
        for (BlockPos pos : positions) {
            if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) {
                messages.send(player, "preview-invalid-height");
                return;
            }
        }
        BlockData previewData = Bukkit.createBlockData(PREVIEW_MATERIAL);
        for (BlockPos pos : positions) {
            player.sendBlockChange(new Location(world, pos.x(), pos.y(), pos.z()), previewData);
        }
        previews.put(player.getUniqueId(), new PreviewState(world.getName(), Set.copyOf(positions)));
    }

    /** Restore the current real block state after a client-only preview. */
    private void clearSelectionPreview(Player player) {
        if (player == null) return;
        PreviewState preview = previews.remove(player.getUniqueId());
        if (preview == null) return;
        World world = Bukkit.getWorld(preview.world());
        if (world == null || !world.getName().equals(player.getWorld().getName())) return;
        for (BlockPos pos : preview.positions()) {
            player.sendBlockChange(new Location(world, pos.x(), pos.y(), pos.z()),
                    world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData());
        }
    }

    private void clearAllPreviews() {
        for (Player player : Bukkit.getOnlinePlayers()) clearSelectionPreview(player);
        previews.clear();
    }

    private void submit(Player player, Selection selection, OperationKind operation) {
        submit(player, selection, operation, null, false, Set.of());
    }

    private void submit(Player player, Selection selection, OperationKind operation, Material filter, boolean keepOnly) {
        submit(player, selection, operation, filter, keepOnly, Set.of());
    }

    private void submit(Player player, Selection selection, OperationKind operation, Material filter,
                        boolean keepOnly, Set<Material> excluded) {
        if (!player.hasPermission("maxfastbuild.use")) {
            sendProtocol(player, "error", "maxfastbuild.error.no_permission", Map.of("permission", "maxfastbuild.use"));
            return;
        }
        if (selection.first() == null || selection.second() == null
                || (selection.mode() == BuildMode.ARC && selection.third() == null)) {
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
        enqueueCommand(player, new QueuedCommand(selection, operation, filter, keepOnly, excluded));
    }

    /** Replace matching blocks inside the current pos1/pos2 cuboid. */
    private void handleReplaceCommand(Player player, Selection current, String[] args) {
        if (args.length < 3 || current.first() == null || current.second() == null) {
            messages.send(player, "replace-usage");
            return;
        }
        Material origin = resolveBlockArgument(args[1], true);
        Material replacement = resolveBlockArgument(args[2]);
        if (origin == null) {
            messages.send(player, "replace-invalid-origin", args[1]);
            return;
        }
        if (replacement == null || PaperWorldAccess.isForbiddenPlaceMaterial(replacement)) {
            messages.send(player, "replace-invalid-new", args[2]);
            return;
        }
        try {
            Set<Material> excluded = parseExceptMaterials(args, 3);
            Selection region = current.withMaterial(replacement.getKey().toString())
                    .withWorld(player.getWorld().getName());
            submit(player, region, OperationKind.PLACE, origin, false, excluded);
        } catch (IllegalArgumentException ex) {
            messages.send(player, "replace-invalid-except", ex.getMessage());
        }
    }

    private static Material resolveBlockArgument(String raw) {
        return resolveBlockArgument(raw, false);
    }

    private static Material resolveBlockArgument(String raw, boolean allowAir) {
        if (raw == null) return null;
        String key = raw.contains(":") ? raw : "minecraft:" + raw;
        Material material = Material.matchMaterial(key, false);
        if (material == null) material = Material.matchMaterial(raw, false);
        return material != null && material.isBlock() && (allowAir || !material.isAir()) ? material : null;
    }

    /** Parse one material, comma-separated materials, or bracketed material arrays. */
    static Set<Material> parseExceptMaterials(String[] args, int start) {
        if (args == null || start >= args.length) return Set.of();
        String raw = String.join(",", Arrays.copyOfRange(args, start, args.length)).trim();
        if (raw.startsWith("[")) raw = raw.substring(1);
        if (raw.endsWith("]")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isBlank()) return Set.of();
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String token : raw.split("[,\\s]+")) {
            if (token.isBlank()) continue;
            token = token.replaceAll("^[\\\"']|[\\\"']$", "");
            Material material = resolveBlockArgument(token, true);
            if (material == null) throw new IllegalArgumentException(token);
            result.add(material);
        }
        return Set.copyOf(result);
    }

    private void enqueueCommand(Player player, QueuedCommand command) {
        int maxQueue = getConfig().getInt("execution.max-queued-commands-per-player", 32);
        Queue<QueuedCommand> queue = commandQueues.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        if (queue.size() >= maxQueue) {
            sendProtocol(player, "error", "maxfastbuild.error.queue_full", Map.of("limit", maxQueue));
            return;
        }
        queue.add(command);
        // The command is now authoritative. Remove client-only blocks before the
        // asynchronous planner starts so the player never sees stale positions.
        clearSelectionPreview(player);
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
        ServerLimits limits = limits();
        Optional<RequestLimitValidator.Violation> violation = RequestLimitValidator.region(
                shapeRequest(next.selection()).bounds(), limits);
        if (violation.isPresent()) {
            sendLimitError(player, violation.get());
            return;
        }
        pendingBuilds.put(playerId, new PendingBuild(player, next.selection(), next.operation(),
                limits.maxRegionBlocks(), limits.maxAffectedBlocks(), next.filter(), next.keepOnly(), next.excluded()));
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
        int hollow = 0;
        if (args.length > 7) {
            String mode = args[7].toLowerCase(Locale.ROOT);
            switch (mode) {
                case "destroy" -> { }
                case "hollow", "outline" -> hollow = 1;
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
                    pending.positions = new DefaultShapeGenerator().generate(shapeRequest(pending.selection),
                            shapeGenerationLimit(pending.maxBlocks));
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
            if (!pending.excluded.isEmpty()) {
                Material beforeMaterial = Bukkit.createBlockData(before).getMaterial();
                if (pending.excluded.contains(beforeMaterial)) continue;
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
                if (pending.mutations.size() > pending.maxAffectedBlocks) {
                    return new PlanningError("maxfastbuild.error.affected_too_large",
                            Map.of("actual", pending.mutations.size(), "limit", pending.maxAffectedBlocks));
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

        Bounds selectionBounds = shapeRequest(selection).bounds();
        BuildPlan plan = new BuildPlan(selection.world(), operation, selectionBounds, mutations);
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
        PaperInventoryHelper.SearchOptions search = inventorySearch(player, searchShulkers);
        List<PaperInventoryHelper.ItemSource> regionSources = search.sources(player);
        String itemKey = PaperInventoryHelper.itemKeyFromBlockState(selection.material());
        long need = mutations.size();
        if (requireMaterials) {
            org.bukkit.Material resolved = PaperInventoryHelper.resolveMaterial(itemKey);
            if (resolved == null || PaperWorldAccess.isForbiddenPlaceMaterial(resolved)) {
                sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", String.valueOf(selection.material())));
                return;
            }
            long have = PaperInventoryHelper.count(regionSources, itemKey,
                    search.requiredBuckets, search.fireRequiresFlint);
            if (have < need) {
                sendMaterialError(player, itemKey, need, have,
                        search.fireRequiresFlint, search.requiredBuckets);
                return;
            }
        }

        UUID taskId = UUID.randomUUID();
        String transactionId = taskId + ":withdraw";
        boolean tookMoney = false;
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
        PaperInventoryHelper.RemovalLedger regionRemovals = null;
        if (requireMaterials) {
            regionRemovals = new PaperInventoryHelper.RemovalLedger();
            auditContainerTakes(player, selection.world(), true, regionSources, null);
            long removed = PaperInventoryHelper.take(regionSources, itemKey, need,
                    search.requiredBuckets, search.fireRequiresFlint, regionRemovals);
            if (removed < need) {
                auditContainerRefunds(player.getUniqueId(), player.getName(), selection.world(), regionRemovals);
                regionRemovals.restoreAll();
                if (tookMoney) refundMoney(player, taskId, charge.total(), transactionId);
                sendMaterialError(player, itemKey, need, removed,
                        search.fireRequiresFlint, search.requiredBuckets);
                return;
            }
        }

        Instant now = Instant.now();
        BuildTask task = new BuildTask(taskId, player.getUniqueId(), player.getName(), plan, TaskStatus.QUEUED,
                0, 0, Set.of(), null, tookMoney ? charge.total() : BigDecimal.ZERO, BigDecimal.ZERO, now, now, null);
        try {
            executor.enqueue(task);
        } catch (RuntimeException ex) {
            if (regionRemovals != null) {
                auditContainerRefunds(player.getUniqueId(), player.getName(), selection.world(), regionRemovals);
                regionRemovals.restoreAll();
            }
            compensate(player, taskId, tookMoney ? charge.total() : BigDecimal.ZERO, transactionId, ex);
            return;
        }
        if (regionRemovals != null) taskRemovals.put(taskId, regionRemovals);
        sendProtocol(player, "accepted", "maxfastbuild.task.accepted", Map.of(
                "taskId", taskId.toString(),
                "blocks", mutations.size(),
                "affectedBlocks", mutations.size(),
                "regionBlocks", selectionBounds.volume(),
                "sizeX", selectionBounds.sizeX(),
                "sizeY", selectionBounds.sizeY(),
                "sizeZ", selectionBounds.sizeZ(),
                "charge", charge.total().toPlainString()));
    }

    /** Gzip magic bytes (0x1f 0x8b) — paste envelopes are gzipped JSON, legacy envelopes are plain JSON. */
    private static boolean isGzipPayload(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    private static PasteRegionMetrics pasteRegionMetrics(List<PasteTransfer.Region> regions) {
        if (regions == null || regions.isEmpty()) throw new IllegalArgumentException("missing_region_metadata");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long volume = 0;
        for (PasteTransfer.Region region : regions) {
            minX = Math.min(minX, region.minX());
            minY = Math.min(minY, region.minY());
            minZ = Math.min(minZ, region.minZ());
            maxX = Math.max(maxX, region.maxX());
            maxY = Math.max(maxY, region.maxY());
            maxZ = Math.max(maxZ, region.maxZ());
            try {
                volume = Math.addExact(volume, region.volume());
            } catch (ArithmeticException ex) {
                volume = Long.MAX_VALUE;
            }
        }
        return new PasteRegionMetrics(new Bounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)),
                volume, List.copyOf(regions));
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
        int entityLimit = payload.instant() ? limits().maxInstantEntities() : limits().maxNormalEntities();
        if (payload.entities().size() > entityLimit) {
            sendProtocol(player, "error", "maxfastbuild.paste.too_many_entities", Map.of("limit", entityLimit));
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
        ServerLimits limits = limits();
        boolean instant = assembled.instant();
        boolean skipContents = assembled.skipContents();
        PasteRegionMetrics regionMetrics;
        try {
            regionMetrics = pasteRegionMetrics(assembled.regions());
        } catch (RuntimeException ex) {
            sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "invalid_region_metadata"));
            return;
        }
        Optional<RequestLimitValidator.Violation> regionViolation = RequestLimitValidator.region(
                regionMetrics.sizeX(), regionMetrics.sizeY(), regionMetrics.sizeZ(), regionMetrics.volume(), limits);
        if (regionViolation.isPresent()) {
            sendLimitError(player, regionViolation.get());
            return;
        }
        int[] origin = assembled.origin();
        List<String> palette = assembled.palette();
        Map<BlockPos, PastePos> positionMap = new LinkedHashMap<>();
        for (PasteTransfer.Entry entry : assembled.entries()) {
            if (entry.paletteIndex() >= palette.size()) {
                debugLog("paste rejected player=" + player.getName() + " reason=palette_index_out_of_range");
                sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "palette_index_out_of_range"));
                return;
            }
            String raw = palette.get(entry.paletteIndex());
            // Palette entries carry block-entity SNBT appended after the state: "minecraft:chest{...}".
            int brace = raw.indexOf('{');
            String target = brace >= 0 ? raw.substring(0, brace) : raw;
            String targetNbt = brace >= 0 ? raw.substring(brace) : null;
            if (targetNbt != null && PaperNbtHelper.parseCompound(targetNbt) == null) {
                debugLog("paste rejected player=" + player.getName()
                        + " reason=unparseable_nbt raw=\"" + raw + "\"");
                sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "unparseable_nbt"));
                return;
            }
            Material material;
            try {
                material = Bukkit.createBlockData(target).getMaterial();
            } catch (IllegalArgumentException ex) {
                sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", target));
                return;
            }
            if (skipContents && targetNbt != null) {
                // Empty-container paste: drop every billable content field (Items/Book/RecordItem/
                // pot item/sherds) so nothing is billed and nothing is placed inside the tile.
                String stripped = PaperNbtHelper.stripContentFields(targetNbt, material);
                targetNbt = stripped == null ? targetNbt : stripped;
            }
            if (material.isAir() || !material.isBlock() || RestrictedMaterials.isForbiddenPlace(material)) {
                continue;
            }
            PastePos pastePos = new PastePos(new BlockPos(origin[0] + entry.dx(), origin[1] + entry.dy(), origin[2] + entry.dz()), target, targetNbt);
            if (!regionMetrics.contains(pastePos.position())) {
                sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "entry_outside_region"));
                return;
            }
            PastePos previous = positionMap.putIfAbsent(pastePos.position(), pastePos);
            if (previous != null && !previous.equals(pastePos)) {
                sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "conflicting_duplicate_position"));
                return;
            }
        }
        List<PastePos> positions = new ArrayList<>(positionMap.values());
        if (positions.isEmpty()) {
            debugLog("paste rejected player=" + player.getName() + " reason=no_placeable_blocks");
            sendProtocol(player, "error", "maxfastbuild.error.no_changes", Map.of());
            return;
        }
        if (regionMetrics.volume() != Long.MAX_VALUE && positions.size() > regionMetrics.volume()) {
            sendProtocol(player, "error", "maxfastbuild.error.malformed", Map.of("reason", "entries_exceed_region"));
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
        // Validate pasted entities (minecarts/boats/armor stands/mobs). Invalid entities are skipped
        // individually, never allowed to abort the whole paste.
        List<PendingEntity> entities = new ArrayList<>();
        List<PastePrecheckIssue> entityIssues = new ArrayList<>();
        if (assembled.entities() != null) {
            Object registry = PaperNbtHelper.registryAccess(player.getWorld());
            for (PasteTransfer.EntityEntry entity : assembled.entities()) {
                try {
                    String entityNbt = skipContents ? PaperNbtHelper.stripEntityContents(entity.nbt()) : entity.nbt();
                    PaperEntityHelper.EntityData data = PaperEntityHelper.validate(entity.type(), entityNbt, registry);
                    entities.add(new PendingEntity(data, entity.x(), entity.y(), entity.z()));
                } catch (PaperEntityHelper.EntityRejectException ex) {
                    debugLog("paste entity rejected player=" + player.getName()
                            + " type=" + entity.type() + " reason=" + ex.reason);
                    entityIssues.add(new PastePrecheckIssue("entity", entity.type(), "rejected: " + ex.reason, false));
                } catch (LinkageError | RuntimeException ex) {
                    debugLog("paste entity unexpected error player=" + player.getName()
                            + " type=" + entity.type() + " error=" + shortError(ex));
                    entityIssues.add(new PastePrecheckIssue("entity", entity.type(), shortError(ex), true));
                }
            }
        }
        int entityLimit = instant ? limits.maxInstantEntities() : limits.maxNormalEntities();
        if (entities.size() > entityLimit) {
            sendProtocol(player, "error", "maxfastbuild.paste.too_many_entities", Map.of("limit", entityLimit));
            return;
        }
        int entityChunkLimit = instant ? limits.maxInstantEntitiesPerChunk() : limits.maxNormalEntitiesPerChunk();
        if (entityChunkLimit > 0) {
            Map<String, Integer> chunkCounts = new HashMap<>();
            for (PendingEntity entity : entities) {
                String chunk = Math.floorDiv((int) Math.floor(entity.x()), 16) + ","
                        + Math.floorDiv((int) Math.floor(entity.z()), 16);
                int count = chunkCounts.merge(chunk, 1, Integer::sum);
                if (count > entityChunkLimit) {
                    sendProtocol(player, "error", "maxfastbuild.paste.too_many_entities_per_chunk",
                            Map.of("limit", entityChunkLimit));
                    return;
                }
            }
        }
        PendingPaste pending = new PendingPaste(player, worldName, instant, positions, entities,
                regionMetrics.bounds(), regionMetrics.volume(), limits.maxAffectedBlocks());
        pending.issues.addAll(entityIssues);
        pendingPastes.put(player.getUniqueId(), pending);
        debugLog("paste assembled player=" + player.getName()
                + " blocks=" + positions.size() + " entities=" + entities.size() + " instant=" + instant);
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
            PlanningError error;
            try {
                error = advancePastePlanning(pending, perPlayer);
            } catch (LinkageError | RuntimeException ex) {
                // Safety net: never let one paste crash the tick loop or spam errors forever.
                getLogger().severe("Paste planning crashed for " + player.getName() + ": " + ex);
                pending.issues.add(new PastePrecheckIssue("block", "<planning>", shortError(ex), true));
                if (hasFatalPrecheck(pending)) {
                    cancelPastePrecheck(player, pending);
                } else {
                    sendProtocol(player, "error", "maxfastbuild.error.paste_precheck_failed",
                            Map.of("count", 1, "fatal", 1, "detail", shortError(ex)));
                }
                it.remove();
                continue;
            }
            globalRemaining -= (int) (pending.processed - before);
            if (error != null) {
                debugLog("paste planning rejected player=" + player.getName()
                        + " reason=" + error.key());
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
            // Replace-by-default: an unplaceable position (unsafe height, unbreakable occupant,
            // no effective tool, forbidden target) is skipped, never allowed to abort the paste.
            if (pos.y() < minHeight || pos.y() >= maxHeight) {
                pending.planningSkipped++;
                continue;
            }
            String before;
            try {
                before = world.stateAt(pending.world, pos);
            } catch (LinkageError | RuntimeException ex) {
                pending.issues.add(new PastePrecheckIssue("block", pp.targetState(), shortError(ex), true));
                pending.planningSkipped++;
                continue;
            }
            if (before.equals(pp.targetState()) && pp.targetNbt() == null) continue;
            BlockMutation mutation = new BlockMutation(pos, before, pp.targetState(), pp.targetNbt());
            WorldAccess.ValidationResult validation;
            try {
                validation = world.mayMutate(player.getUniqueId(), pending.world, mutation, OperationKind.PLACE);
            } catch (LinkageError | RuntimeException ex) {
                pending.issues.add(new PastePrecheckIssue("block", pp.targetState(), shortError(ex), true));
                pending.planningSkipped++;
                continue;
            }
            if (!validation.allowed()) {
                debugLog("paste planning skipped player=" + player.getName()
                        + " pos=" + pos + " target=" + pp.targetState()
                        + " before=" + before + " reason=" + validation.reason());
                pending.planningSkipped++;
                continue;
            }
            pending.mutations.add(mutation);
            if (PaperWorldAccess.requiresBreakToReplace(before)) {
                pending.replaceBreakCount++;
            }
            if (pending.mutations.size() > pending.maxAffectedBlocks) {
                return new PlanningError("maxfastbuild.error.affected_too_large",
                        Map.of("actual", pending.mutations.size(), "limit", pending.maxAffectedBlocks));
            }
        }
        return null;
    }

    private void finalizePastePlanning(PendingPaste pending) {
        Player player = pending.player;
        // Any unexpected precheck error (missing NMS class, etc.) cancels the whole paste before
        // any material is deducted or anything is placed — nothing half-built, nothing charged.
        for (PastePrecheckIssue issue : pending.issues) {
            if (issue.fatal()) {
                cancelPastePrecheck(player, pending);
                return;
            }
        }
        List<BlockMutation> mutations = pending.mutations;
        if (mutations.isEmpty()) {
            debugLog("paste finalized player=" + player.getName()
                    + " reason=no_changes plannedSkipped=" + pending.planningSkipped);
            sendProtocol(player, "error", "maxfastbuild.error.no_changes", Map.of());
            return;
        }

        // Place-over-solid survival paste needs at least one mining tool; per-position replace
        // feasibility is enforced during execution (unbreakable / wrong tool / durability exhaustion
        // skip that position instead of aborting the whole paste).
        if (pending.replaceBreakCount > 0 && player.getGameMode() != GameMode.CREATIVE
                && !BreakToolHelper.hasAnyMiningTool(player)) {
            sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool", Map.of("reason", "no_tool"));
            return;
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
        BillingPolicy policy = billing();
        BillingPolicy.Charge charge = policy.quote(plan, pending.replaceBreakCount);
        if (pending.instant) {
            BigDecimal mult = instantMultiplier();
            java.util.function.Function<BigDecimal, BigDecimal> scaled = v -> v.multiply(mult).setScale(policy.fractionalDigits(), java.math.RoundingMode.HALF_UP);
            charge = new BillingPolicy.Charge(scaled.apply(charge.operation()), scaled.apply(charge.area()), scaled.apply(charge.blocks()), scaled.apply(charge.total()));
        }
        boolean requireMaterials = player.getGameMode() != GameMode.CREATIVE
                && !player.hasPermission("maxfastbuild.bypass.materials");
        boolean searchShulkers = getConfig().getBoolean("inventory.search-shulker-boxes", false);
        if (searchShulkers && getConfig().getBoolean("inventory.require-shulker-permission", false)
                && !player.hasPermission("maxfastbuild.material.shulker")) {
            searchShulkers = false;
        }
        PaperInventoryHelper.SearchOptions search = new PaperInventoryHelper.SearchOptions(
                searchShulkers,
                getConfig().getBoolean("inventory.search-containers", true),
                getConfig().getInt("inventory.container-search-radius", 5),
                fluidBucketRequirement(),
                getConfig().getBoolean("inventory.fire-requires-flint-and-steel", true));
        List<PaperInventoryHelper.ItemSource> sources = search.sources(player);
        debugLog("paste diag sources player=" + player.getName()
                + " count=" + sources.size()
                + " searchShulkers=" + searchShulkers
                + " searchContainers=" + getConfig().getBoolean("inventory.search-containers", true)
                + " radius=" + getConfig().getInt("inventory.container-search-radius", 5));
        for (int si = 0; si < sources.size(); si++) {
            Location c = sources.get(si).container();
            debugLog("  diag source[" + si + "] " + (c != null
                    ? "container " + c.getBlockX() + "," + c.getBlockY() + "," + c.getBlockZ()
                    : "player"));
        }
        PaperInventoryHelper.RemovalLedger removals = new PaperInventoryHelper.RemovalLedger();
        pending.removals = removals;

        // A paste uses many block types plus (for containers) every item inside their NBT,
        // and (for entities) the minecart/boat/armor-stand item plus its container contents.
        PasteMaterials needs;
        try {
            needs = collectPasteMaterials(player.getWorld(), mutations);
            addEntityMaterials(needs, pending.entities);
        } catch (PasteRejectException reject) {
            debugLog("paste rejected player=" + player.getName() + " reason=" + reject.key);
            sendProtocol(player, "error", reject.key, reject.data);
            return;
        } catch (LinkageError | RuntimeException ex) {
            pending.issues.add(new PastePrecheckIssue("item", "<materials>", shortError(ex), true));
            cancelPastePrecheck(player, pending);
            return;
        }
        pending.needs = needs;
        lastPasteNeeds.put(player.getUniqueId(), needs);
        if (requireMaterials) {
            // Blocks and container contents draw from the same item pool. Verify the per-material
            // TOTAL once up front, so a combined shortage (e.g. 68 shulker-box blocks + 454 boxes
            // stored inside containers vs 365 boxes on hand) is reported clearly instead of passing
            // every per-entry check and then failing midway through the sequential takes.
            Map<String, Long> totalNeeds = new LinkedHashMap<>(needs.blocks);
            for (Map.Entry<org.bukkit.inventory.ItemStack, Long> entry : needs.contents.entrySet()) {
                Material type = entry.getKey().getType();
                if (type == null || type.isAir() || RestrictedMaterials.isForbiddenItem(type)) continue;
                totalNeeds.merge(type.getKey().toString(), entry.getValue(), Long::sum);
            }
            for (Map.Entry<String, Long> entry : totalNeeds.entrySet()) {
                long have = PaperInventoryHelper.count(sources, entry.getKey(),
                        search.requiredBuckets, search.fireRequiresFlint);
                if (have < entry.getValue()) {
                    debugLog("paste materials insufficient total player=" + player.getName()
                            + " material=" + entry.getKey() + " need=" + entry.getValue() + " have=" + have);
                    sendMaterialError(player, entry.getKey(), entry.getValue(), have,
                            search.fireRequiresFlint, search.requiredBuckets);
                    return;
                }
            }
            for (Map.Entry<String, Long> entry : needs.blocks.entrySet()) {
                Material resolved = PaperInventoryHelper.resolveMaterial(entry.getKey());
                // needs.blocks mixes block items and entity billable items (minecarts, boats, armor
                // stands are items but not blocks), so forbid only genuinely banned materials, not
                // non-block items.
                if (resolved == null || RestrictedMaterials.isForbiddenItem(resolved)) {
                    sendProtocol(player, "error", "maxfastbuild.error.invalid_material", Map.of("material", entry.getKey()));
                    return;
                }
                long have = PaperInventoryHelper.count(sources, entry.getKey(),
                        search.requiredBuckets, search.fireRequiresFlint);
                if (have < entry.getValue()) {
                    debugLog("paste materials insufficient player=" + player.getName()
                            + " material=" + entry.getKey() + " need=" + entry.getValue() + " have=" + have);
                    sendMaterialError(player, entry.getKey(), entry.getValue(), have,
                            search.fireRequiresFlint, search.requiredBuckets);
                    return;
                }
            }
            // Contents are billed from whatever the paste actually places: an empty-container paste
            // has already had its storage fields stripped, so only preserved items (lectern books,
            // jukebox records, entity items) remain and must be supplied.
            for (Map.Entry<org.bukkit.inventory.ItemStack, Long> entry : needs.contents.entrySet()) {
                if (RestrictedMaterials.isForbiddenItem(entry.getKey().getType())) {
                    sendProtocol(player, "error", "maxfastbuild.error.invalid_material",
                            Map.of("material", entry.getKey().getType().getKey().toString()));
                    return;
                }
                long have = PaperInventoryHelper.countExact(sources, entry.getKey());
                if (have < entry.getValue()) {
                    sendProtocol(player, "error", "maxfastbuild.error.insufficient_materials",
                            insufficientMaterialsData(entry.getKey().getType(), entry.getValue(), have));
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
        if (requireMaterials) {
            auditContainerTakes(player, pending.world, true, sources, null);
            // Take exact container contents first, then block items by type. A player who pre-fills
            // the shulker boxes the paste places therefore has the wood inside them drained first
            // (boxes stay behind), then the (now empty) boxes are taken for the block requirement —
            // instead of blocks-first consuming the filled boxes and leaving the wood untakeable.
            for (Map.Entry<org.bukkit.inventory.ItemStack, Long> entry : needs.contents.entrySet()) {
                long removed = PaperInventoryHelper.takeExact(sources, entry.getKey(), entry.getValue(), removals);
                if (removed < entry.getValue()) {
                    auditContainerRefunds(player.getUniqueId(), player.getName(), pending.world, removals);
                    removals.restoreAll();
                    if (tookMoney) refundMoney(player, taskId, charge.total(), transactionId);
                    sendProtocol(player, "error", "maxfastbuild.error.insufficient_materials",
                            insufficientMaterialsData(entry.getKey().getType(), entry.getValue(), removed));
                    return;
                }
            }
            for (Map.Entry<String, Long> entry : needs.blocks.entrySet()) {
                long removed = PaperInventoryHelper.take(sources, entry.getKey(), entry.getValue(),
                        search.requiredBuckets, search.fireRequiresFlint, removals);
                if (removed < entry.getValue()) {
                    auditContainerRefunds(player.getUniqueId(), player.getName(), pending.world, removals);
                    removals.restoreAll();
                    if (tookMoney) refundMoney(player, taskId, charge.total(), transactionId);
                    sendMaterialError(player, entry.getKey(), entry.getValue(), removed,
                            search.fireRequiresFlint, search.requiredBuckets);
                    return;
                }
            }
            tookItems = true;
        }

        // Notify the player when some blocks were skipped during planning (protected, unbreakable
        // occupant, unsupported NBT, etc.) or entities were rejected, so they know the paste is incomplete.
        long entitySkips = pending.issues.stream().filter(i -> !i.fatal() && "entity".equals(i.kind())).count();
        if (pending.planningSkipped > 0 || entitySkips > 0) {
            debugLog("paste planning skipped total player=" + player.getName()
                    + " blocks=" + pending.planningSkipped + " entities=" + entitySkips);
            sendProtocol(player, "warning", "maxfastbuild.paste.blocks_skipped",
                    Map.of("skipped", pending.planningSkipped, "planned", mutations.size(),
                            "entitySkipped", entitySkips));
        }

        // Instant pastes execute synchronously here; everything else enqueues as a rate-limited task.
        if (pending.instant) {
            settleInstant(player, pending, plan, charge, transactionId, tookMoney);
            return;
        }

        Instant now = Instant.now();
        BuildTask task = new BuildTask(taskId, player.getUniqueId(), player.getName(), plan, TaskStatus.QUEUED,
                0, 0, Set.of(), null, tookMoney ? charge.total() : BigDecimal.ZERO, BigDecimal.ZERO, now, now, null);
        try {
            executor.enqueue(task);
        } catch (RuntimeException ex) {
            if (tookItems) {
                auditContainerRefunds(player.getUniqueId(), player.getName(), pending.world, removals);
                removals.restoreAll();
            }
            compensate(player, taskId, tookMoney ? charge.total() : BigDecimal.ZERO, transactionId, ex);
            return;
        }
        // Queued paste entities spawn when the block task completes (spawn/refund handled in settlePartial).
        if (!pending.entities.isEmpty()) {
            taskEntities.put(taskId, pending.entities);
        }
        if (tookItems) {
            taskRemovals.put(taskId, removals);
        }
        sendProtocol(player, "accepted", "maxfastbuild.task.accepted", Map.of(
                "taskId", taskId.toString(),
                "blocks", mutations.size(),
                "affectedBlocks", mutations.size(),
                "regionBlocks", pending.regionBlocks,
                "sizeX", pending.regionBounds.sizeX(),
                "sizeY", pending.regionBounds.sizeY(),
                "sizeZ", pending.regionBounds.sizeZ(),
                "entities", pending.entities.size(),
                "charge", charge.total().toPlainString()));
    }

    /** Pick the clearest message for a failed material check: fluids tell the player to bring buckets,
     *  fire tells them to bring a flint and steel, everything else uses the generic insufficient list. */
    private void sendMaterialError(Player player, String materialKey, long need, long have,
                                   boolean fireRequiresFlint, int requiredBuckets) {
        Material resolved = PaperInventoryHelper.resolveMaterial(materialKey);
        if (resolved != null && PaperInventoryHelper.isFluid(resolved)) {
            sendProtocol(player, "error", "maxfastbuild.error.requires_buckets",
                    Map.of("material", materialKey, "buckets", requiredBuckets));
        } else if (fireRequiresFlint && resolved != null && PaperInventoryHelper.isFire(resolved)) {
            sendProtocol(player, "error", "maxfastbuild.error.requires_flint_and_steel",
                    Map.of("material", materialKey));
        } else {
            Map<String, Object> data = new HashMap<>();
            data.put("need", need);
            data.put("have", have);
            data.put("material", materialKey);
            if (resolved != null && SeedCatalog.isSeeded(resolved)) {
                data.put("seedFarm", true);
            }
            sendProtocol(player, "error", "maxfastbuild.error.insufficient_materials", data);
        }
    }

    /** Data for an {@code insufficient_materials} event; flags {@code seedFarm} when the material is seed-catalyzable. */
    private Map<String, Object> insufficientMaterialsData(Material material, long need, long have) {
        Map<String, Object> data = new HashMap<>();
        data.put("need", need);
        data.put("have", have);
        data.put("material", material == null ? "" : material.getKey().toString());
        if (material != null && SeedCatalog.isSeeded(material)) {
            data.put("seedFarm", true);
        }
        return data;
    }

    /** Cancel the paste with a detailed precheck-failure report to the player + admin log. */
    private void cancelPastePrecheck(Player player, PendingPaste pending) {        StringBuilder detail = new StringBuilder();
        int fatal = 0, total = 0;
        for (PastePrecheckIssue issue : pending.issues) {
            total++;
            if (issue.fatal()) fatal++;
            if (detail.length() < 500) {
                if (detail.length() > 0) detail.append("; ");
                detail.append(issue.kind()).append(" ").append(issue.target())
                        .append(" → ").append(issue.detail());
            }
        }
        String text = detail.length() > 500 ? detail.substring(0, 497) + "…" : detail.toString();
        getLogger().severe("Paste precheck failed for " + player.getName()
                + " fatal=" + fatal + " total=" + total + " — " + text);
        debugLog("paste precheck failed player=" + player.getName() + " fatal=" + fatal + " total=" + total);
        sendProtocol(player, "error", "maxfastbuild.error.paste_precheck_failed",
                Map.of("count", total, "fatal", fatal, "detail", text));
    }

    /** Short error description for an unexpected server error (class name + message). */
    private static String shortError(Throwable t) {
        String name = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) return name;
        return name + ": " + msg;
    }

    private static boolean hasFatalPrecheck(PendingPaste pending) {
        for (PastePrecheckIssue issue : pending.issues) {
            if (issue.fatal()) return true;
        }
        return false;
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
        PaperInventoryHelper.RemovalLedger removals = taskRemovals.remove(task.id());
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
        // Refund only money the player actually paid (bypass.cost / disabled economy => charged == 0),
        // and never more than what was charged.
        if (refund.signum() > 0 && task.charged().signum() > 0 && refund.compareTo(task.charged()) > 0) {
            refund = task.charged();
        }

        Player player = Bukkit.getPlayer(task.playerId());
        if (refund.signum() > 0 && task.charged().signum() > 0) {
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
            // Unapplied mutations are exactly the skipped ones plus any never-reached tail, NOT a
            // contiguous prefix: TaskExecutor skips failures and continues, so the first `applied`
            // mutations may not be the ones that applied.
            Set<Integer> unapplied = new HashSet<>(task.skipped());
            for (int i = task.cursor(); i < task.plan().mutations().size(); i++) unapplied.add(i);
            auditContainerRefunds(task.playerId(), task.playerName(), task.plan().world(), removals);
            returnUnusedMaterials(player, task.plan(), unapplied, removals);
        }

        // Cross-tick redstone convergence for a completed place task: the last batch's settle
        // happens this tick, but tick-dependent components need a few more ticks to settle.
        if (task.status() == TaskStatus.COMPLETED && task.plan().operation() == OperationKind.PLACE) {
            Set<Integer> skipped = task.skipped();
            List<BlockMutation> all = task.plan().mutations();
            List<BlockPos> appliedPositions = new ArrayList<>(all.size() - skipped.size());
            for (int i = 0; i < all.size(); i++) {
                if (!skipped.contains(i)) appliedPositions.add(all.get(i).position());
            }
            scheduleRedstoneTail(task.plan().world(), appliedPositions);
        }

        // Queued paste entities: spawn once the block task completed; refund their materials if the
        // task was cancelled/aborted before finishing.
        List<PendingEntity> entities = taskEntities.remove(task.id());
        if (entities != null && !entities.isEmpty()) {
            auditContainerRefunds(task.playerId(), task.playerName(), task.plan().world(), removals);
            if (task.status() == TaskStatus.COMPLETED && task.plan().operation() == OperationKind.PLACE) {
                spawnEntities(player, task.plan().world(), entities, removals);
            } else {
                returnEntityMaterials(player, entities, removals);
            }
        }

        auditContainerTakes(task.playerId(), task.playerName(), task.plan().world(), false, null, removals);

        if (player != null) {
            debugLog("task settled player=" + player.getName() + " taskId=" + task.id()
                    + " applied=" + appliedCount + " planned=" + planned + " refund=" + refund.toPlainString());
            String key = missed > 0 ? "maxfastbuild.task.partial" : "maxfastbuild.task.completed";
            sendProtocol(player, "completed", key,
                    Map.of("applied", appliedCount, "planned", planned, "cost", task.charged().toPlainString(),
                            "refund", refund.toPlainString()));
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
            sender.sendMessage("/mfbadmin giveall <player> [exceptMaterial] - give all paste materials except one");
            sender.sendMessage("/mfbadmin clear <player> - clear the player's inventory and ground drops");
            sender.sendMessage("/mfbadmin unpack <player> - expand chest-NBT items into shulker boxes");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            mergeConfigDefaults();
            reloadConfig();
            serverLimits = loadServerLimits();
            if (pastes != null) pastes.clear();
            pastes = new PasteAccumulator(Clock.systemUTC(), Duration.ofSeconds(120),
                    serverLimits.maxPasteParts(), serverLimits.maxBlocksPerPart(), serverLimits.maxPasteTotalBlocks());
            protocol = new SecureProtocol(Clock.systemUTC(),
                    Duration.ofMinutes(getConfig().getLong("protocol.session-minutes", 30)),
                    serverLimits.maxPayloadBytes());
            sessions.clear();
            globalBudgetPerTick = Math.max(0, getConfig().getInt("execution.global-blocks-per-tick", 4));
            planningGlobalBudgetPerTick = Math.max(0, getConfig().getInt("execution.planning.global-blocks-per-tick", 2000));
            refreshDebugFlags();
            SeedCatalog.reload(getConfig());
            messages.reload();
            for (Player online : Bukkit.getOnlinePlayers()) issueSession(online);
            sender.sendMessage(messages.component("reloaded"));
            getLogger().info("Reloaded config; CLI language=" + messages.language());
        } else if (args[0].equalsIgnoreCase("recovery")) {
            messages.send(sender, "recovery", tasks.recoverable().size(), ledger.pending().size());
        } else if (args[0].equalsIgnoreCase("torches") && args.length >= 7) {
            dumpTorches(sender, args);
        } else if (args[0].equalsIgnoreCase("giveall") && args.length >= 2) {
            giveAllPasteMaterials(sender, args);
        } else if ((args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("clearinv")) && args.length >= 2) {
            clearPlayerAndDrops(sender, args);
        } else if (args[0].equalsIgnoreCase("unpack") && args.length >= 2) {
            unpackPasteStorage(sender, args);
        } else {
            messages.send(sender, "admin-unknown");
        }
        return true;
    }

    /** Debug/admin helper: empty a player's inventory and remove every dropped item in their world. */
    private void clearPlayerAndDrops(org.bukkit.command.CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("Player not found: " + args[1]);
            return;
        }
        target.getInventory().clear();
        int removed = PaperInventoryHelper.clearWorldDrops(target);
        getLogger().info("[MaxFastBuild] admin clear player=" + target.getName()
                + " dropsRemoved=" + removed);
        sender.sendMessage("Cleared " + target.getName() + "'s inventory and removed " + removed + " dropped items.");
    }

    /** Debug/admin helper: expand chest-with-NBT items back into the shulker boxes they hold. */
    private void unpackPasteStorage(org.bukkit.command.CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("Player not found: " + args[1]);
            return;
        }
        int items = PaperInventoryHelper.unpackChestStorage(target);
        getLogger().info("[MaxFastBuild] admin unpack player=" + target.getName()
                + " items=" + items);
        sender.sendMessage("Unpacked " + items + " items into " + target.getName() + "'s inventory.");
    }

    /**
     * Debug/admin helper: give a player every item required by their last prechecked paste,
     * except an optionally excluded material. Used to clone a large machine in survival mode
     * without grinding every component.
     * Usage: {@code /mfbadmin giveall <player> [exceptMaterial]}
     */
    private void giveAllPasteMaterials(org.bukkit.command.CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("Player not found: " + args[1]);
            return;
        }
        PasteMaterials needs = lastPasteNeeds.get(target.getUniqueId());
        if (needs == null) {
            sender.sendMessage("No computed paste materials for " + target.getName()
                    + ". Paste once (even if rejected) so materials are computed.");
            return;
        }
        String except = args.length >= 3 ? args[2] : null;
        long givenBlocks = 0;
        long skippedBlocks = 0;
        java.util.List<org.bukkit.inventory.ItemStack> supplies = new java.util.ArrayList<>();
        java.util.Set<Material> seedCatalysts = java.util.EnumSet.noneOf(Material.class);
        java.util.Set<Material> seedTargets = java.util.EnumSet.noneOf(Material.class);
        for (Map.Entry<String, Long> entry : needs.blocks.entrySet()) {
            if (except != null && except.equalsIgnoreCase(entry.getKey())) {
                skippedBlocks += entry.getValue();
                continue;
            }
            Material mat = PaperInventoryHelper.resolveMaterial(entry.getKey());
            if (mat == null || !mat.isItem()) continue;
            org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(mat);
            long packedAmount = entry.getValue();
            if (SeedCatalog.isSeeded(mat) && packedAmount > 0) {
                seedTargets.add(mat);
                seedCatalysts.addAll(SeedCatalog.supplementalCatalysts(mat));
                packedAmount--;
            }
            if (packedAmount > 0) {
                item.setAmount((int) packedAmount);
                supplies.add(item);
            }
            givenBlocks += entry.getValue();
        }
        if (except != null) {
            seedCatalysts.removeIf(catalyst -> except.equalsIgnoreCase(catalyst.getKey().toString()));
        }
        long givenContents = 0;
        long skippedContents = 0;
        for (Map.Entry<org.bukkit.inventory.ItemStack, Long> entry : needs.contents.entrySet()) {
            String key = entry.getKey().getType().getKey().toString();
            if (except != null && except.equalsIgnoreCase(key)) {
                skippedContents += entry.getValue();
                continue;
            }
            org.bukkit.inventory.ItemStack item = entry.getKey().clone();
            long packedAmount = entry.getValue();
            Material material = item.getType();
            if (SeedCatalog.isSeeded(material) && packedAmount > 0) {
                seedTargets.add(material);
                seedCatalysts.addAll(SeedCatalog.supplementalCatalysts(material));
                packedAmount--;
            }
            if (packedAmount > 0) {
                item.setAmount((int) packedAmount);
                supplies.add(item);
            }
            givenContents += entry.getValue();
        }
        java.util.List<org.bukkit.inventory.ItemStack> chests = PaperInventoryHelper.compactStorage(supplies);
        if (chests.isEmpty() && seedTargets.isEmpty() && seedCatalysts.isEmpty()) {
            sender.sendMessage("No materials to give (all excluded by " + except + "?).");
            return;
        }
        PaperInventoryHelper.giveBundles(target, chests);
        for (Material seedTarget : seedTargets) {
            PaperInventoryHelper.giveOrDrop(target, seedTarget.getKey().toString(), 1);
        }
        for (Material catalyst : seedCatalysts) {
            PaperInventoryHelper.giveOrDrop(target, catalyst.getKey().toString(), 1);
        }
        sender.sendMessage("Gave " + target.getName() + " " + givenBlocks + " block items + "
                + givenContents + " container contents, packed into "
                + chests.size() + " chest(s) with NBT"
                + (seedCatalysts.isEmpty() ? "" : " + " + seedCatalysts.size() + " seed catalysts")
                + (except == null ? "." : " (skipped " + skippedBlocks + " + " + skippedContents + " of " + except + ")."));
        getLogger().info("[MaxFastBuild] admin giveall player=" + target.getName()
                + " givenBlocks=" + givenBlocks + " givenContents=" + givenContents
                + " chests=" + chests.size()
                + " except=" + except + " skippedBlocks=" + skippedBlocks + " skippedContents=" + skippedContents);
    }

    /**
     * Debug: log every redstone torch in the given axis-aligned box with its lit state, so a pasted
     * redstone machine can be diffed against the schematic (which stores torch lit states).
     * Usage: {@code /mfbadmin torches <x1> <y1> <z1> <x2> <y2> <z2>}.
     */
    private void dumpTorches(org.bukkit.command.CommandSender sender, String[] args) {
        if (!debugEnabled()) {
            sender.sendMessage("Torch dump requires debug.enabled=true");
            return;
        }
        try {
            World world = sender instanceof Player player ? player.getWorld() : Bukkit.getWorlds().get(0);
            int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
            int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            int minY = Math.max(world.getMinHeight(), Math.min(y1, y2));
            int maxY = Math.min(world.getMaxHeight() - 1, Math.max(y1, y2));
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
            int count = 0, lit = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        Block block = world.getBlockAt(x, y, z);
                        String name = block.getType().name();
                        if (!name.equals("REDSTONE_TORCH") && !name.equals("REDSTONE_WALL_TORCH")) continue;
                        boolean isLit = block.getBlockData() instanceof org.bukkit.block.data.Lightable lightable
                                && lightable.isLit();
                        count++;
                        if (isLit) lit++;
                        getLogger().info("TORCH " + x + "," + y + "," + z + " " + (isLit ? "LIT" : "OFF") + " " + name);
                    }
                }
            }
            getLogger().info("TORCHES total=" + count + " lit=" + lit + " box=" + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ);
            sender.sendMessage("Dumped " + count + " torches (lit=" + lit + ") to the server log");
        } catch (NumberFormatException | IndexOutOfBoundsException ex) {
            messages.send(sender, "admin-unknown");
        }
    }

    /** Clear commands that have not yet become durable BuildTasks. Running tasks cannot be cancelled. */
    private void clearPendingQueue(Player player) {
        if (!active || tasks == null || executor == null) return;
        UUID playerId = player.getUniqueId();
        int cleared = 0;
        if (pendingBuilds.remove(playerId) != null) cleared++;
        if (pendingPastes.remove(playerId) != null) cleared++;
        lastPasteNeeds.remove(playerId);
        Queue<QueuedCommand> queued = commandQueues.remove(playerId);
        if (queued != null) cleared += queued.size();
        if (messages != null) {
            if (cleared == 0) messages.send(player, "cancel-none");
            else messages.send(player, "cancel-done", cleared);
        }
    }

    /** Clear all server-side selection anchors and the client-only preview. */
    private void clearSelectionPoints(Player player) {
        selections.remove(player.getUniqueId());
        clearSelectionPreview(player);
        if (messages != null) messages.send(player, "clearpos-done");
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

    /** Water/lava buckets required in inventory to place an unlimited amount of that fluid. */
    private int fluidBucketRequirement() {
        return Math.max(1, getConfig().getInt("inventory.fluid-bucket-requirement", 2));
    }

    /** Search options for single/region place: shulker nested items plus nearby containers. */
    private PaperInventoryHelper.SearchOptions inventorySearch(Player player, boolean searchShulkers) {
        return new PaperInventoryHelper.SearchOptions(searchShulkers,
                getConfig().getBoolean("inventory.search-containers", true),
                getConfig().getInt("inventory.container-search-radius", 5),
                fluidBucketRequirement(),
                getConfig().getBoolean("inventory.fire-requires-flint-and-steel", true));
    }

    /**
     * Audit hooks for material takes from nearby containers: snapshot each container (CoreProtect)
     * before the take, and record every actual container removal (Prism) after a successful take.
     * Player-inventory-only takes are skipped (no container was mutated).
     */
    private void auditContainerTakes(Player player, String world, boolean beforeTake,
                                     List<PaperInventoryHelper.ItemSource> sources,
                                     PaperInventoryHelper.RemovalLedger removals) {
        auditContainerTakes(player.getUniqueId(), player.getName(), world, beforeTake, sources, removals);
    }

    private void auditContainerTakes(UUID playerId, String playerName, String world, boolean beforeTake,
                                     List<PaperInventoryHelper.ItemSource> sources,
                                     PaperInventoryHelper.RemovalLedger removals) {
        if (!audit.available()) return;
        if (beforeTake) {
            if (sources == null) return;
            for (PaperInventoryHelper.ItemSource source : sources) {
                org.bukkit.Location loc = source.container();
                if (loc == null) continue;
                audit.beforeContainerMutation(playerId, playerName, world,
                        new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            }
            return;
        }
        if (removals == null) return;
        for (PaperInventoryHelper.Removal removal : removals.remainingRemovals()) {
            org.bukkit.Location loc = removal.host().getLocation();
            if (loc == null) continue;
            org.bukkit.Material mat = removal.material() != null ? removal.material()
                    : (removal.template() != null ? removal.template().getType() : null);
            long amount = removal.material() != null ? removal.materialAmount() : removal.templateAmount();
            if (mat == null || amount <= 0) continue;
            audit.recordItemRemoval(playerId, playerName, loc.getWorld().getName(),
                    new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                    mat.getKey().toString(), (int) Math.min(amount, Integer.MAX_VALUE));
        }
    }

    /** Snapshot container slots before a cancellation/partial-completion refund writes them back. */
    private void auditContainerRefunds(UUID playerId, String playerName, String world,
                                       PaperInventoryHelper.RemovalLedger removals) {
        if (!audit.available() || removals == null) return;
        Set<BlockPos> seen = new HashSet<>();
        for (PaperInventoryHelper.Removal removal : removals.remainingRemovals()) {
            Location loc = removal.host().getLocation();
            if (loc == null) continue;
            BlockPos pos = new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            if (seen.add(pos)) audit.beforeContainerMutation(playerId, playerName, world, pos);
        }
    }

    /** Instant-paste economy multiplier (0 = free aside from materials; never negative). */
    private BigDecimal instantMultiplier() {
        return new BigDecimal(String.valueOf(getConfig().get("instant-paste.multiplier", 2))).max(BigDecimal.ZERO);
    }

    private ServerLimits loadServerLimits() {
        return new ServerLimits(
                getConfig().getLong("execution.max-region-blocks", 100000),
                getConfig().getLong("execution.max-affected-blocks", 100000),
                getConfig().getLong("execution.max-region-size.x", 200),
                getConfig().getLong("execution.max-region-size.y", 200),
                getConfig().getLong("execution.max-region-size.z", 200),
                getConfig().getInt("protocol.paste.max-parts", PasteTransfer.MAX_PARTS),
                getConfig().getInt("protocol.paste.max-blocks-per-part", PasteTransfer.MAX_BLOCKS_PER_PART),
                getConfig().getInt("protocol.paste.max-total-blocks", PasteAccumulator.MAX_TOTAL_BLOCKS),
                getConfig().getInt("protocol.max-payload-bytes", 131072),
                instantMaxEntities(),
                instantMaxEntitiesPerChunk(),
                PasteTransfer.MAX_NORMAL_ENTITIES,
                PasteTransfer.MAX_NORMAL_ENTITIES_PER_CHUNK);
    }

    private ServerLimits limits() {
        ServerLimits value = serverLimits;
        if (value == null) throw new IllegalStateException("server limits unavailable");
        return value;
    }

    /** Effective instant-paste entity cap, advertised to clients and enforced on the server. */
    private int instantMaxEntities() {
        int configured = Math.max(0, getConfig().getInt("instant-paste.max-entities", 64));
        return Math.min(configured, PasteTransfer.MAX_INSTANT_ENTITIES);
    }

    /** Hard instant-paste entity cap per chunk. */
    private int instantMaxEntitiesPerChunk() {
        int configured = Math.max(0, getConfig().getInt("instant-paste.max-entities-per-chunk", 32));
        return Math.min(configured, PasteTransfer.MAX_INSTANT_ENTITIES_PER_CHUNK);
    }

    /**
     * Per-block materials and exact container contents a paste consumes. The container block itself
     * is billed as one plain block item (its material key); every item inside its {@code Items} NBT
     * is billed as an exact-match item (same type + meta). Throws {@link PasteRejectException} when
     * block-entity NBT cannot be parsed or contains a forbidden/undecodable item — a paste carrying
     * NBT is rejected outright rather than placed empty.
     */
    private static PasteMaterials collectPasteMaterials(World world, List<BlockMutation> mutations) {
        Map<String, Long> blocks = new LinkedHashMap<>();
        Map<org.bukkit.inventory.ItemStack, Long> contents = new LinkedHashMap<>();
        Object registry = world == null ? null : PaperNbtHelper.registryAccess(world);
        for (BlockMutation mutation : mutations) {
            String blockKey = PaperInventoryHelper.itemKeyFromBlockState(mutation.targetState());
            Material blockMaterial = PaperInventoryHelper.resolveMaterial(blockKey);
            // Derived/transient blocks without an inventory item (piston heads, stems, frosted ice)
            // are placed free; fluids and fire are billed as tokens below.
            if (blockMaterial != null && PaperInventoryHelper.isFreeBlock(blockMaterial)) {
                if (mutation.targetNbt() != null) {
                    throw new PasteRejectException("maxfastbuild.error.nbt_unavailable", Map.of());
                }
                continue;
            }
            blocks.merge(blockKey, 1L, Long::sum);
            if (mutation.targetNbt() == null) continue;
            if (registry == null) throw new PasteRejectException("maxfastbuild.error.nbt_unavailable", Map.of());
            Material tileMaterial;
            try {
                tileMaterial = Bukkit.createBlockData(mutation.targetState()).getMaterial();
            } catch (IllegalArgumentException ex) {
                throw new PasteRejectException("maxfastbuild.error.invalid_material",
                        Map.of("material", PaperInventoryHelper.itemKeyFromBlockState(mutation.targetState())));
            }
            PaperNbtHelper.NbtCheck check = PaperNbtHelper.validateForBlock(mutation.targetNbt(), tileMaterial, registry);
            if (check instanceof PaperNbtHelper.NbtCheck.Rejected rejected) {
                String reason = rejected.reason();
                if (reason.startsWith("forbidden_item_in_nbt")) {
                    throw new PasteRejectException("maxfastbuild.error.invalid_material",
                            Map.of("material", reason.substring("forbidden_item_in_nbt".length())));
                }
                throw new PasteRejectException("maxfastbuild.error.nbt_unavailable", Map.of());
            }
            for (PaperNbtHelper.ItemInstance item : ((PaperNbtHelper.NbtCheck.Ok) check).items()) {
                billContentItem(blocks, contents, item.bukkit(), item.count());
            }
        }
        return new PasteMaterials(blocks, contents);
    }

    private record PasteMaterials(Map<String, Long> blocks, Map<org.bukkit.inventory.ItemStack, Long> contents) {}

    /**
     * Bill one container-content item. A container item (shulker box) that carries its own contents
     * is billed like a placed container block: the container item itself by type (so a player's
     * pre-filled box satisfies the box requirement — counted via {@code count(material)}, which sees
     * empty and filled boxes alike) plus every item inside it billed separately. Plain items are
     * billed as exact templates.
     */
    private static void billContentItem(Map<String, Long> blocks,
                                        Map<org.bukkit.inventory.ItemStack, Long> contents,
                                        org.bukkit.inventory.ItemStack item, long count) {
        if (item == null || item.getType().isAir() || count <= 0) return;
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta bsm
                && bsm.hasBlockState()
                && bsm.getBlockState() instanceof org.bukkit.inventory.InventoryHolder holder) {
            blocks.merge(item.getType().getKey().toString(), count, Long::sum);
            for (org.bukkit.inventory.ItemStack inner : holder.getInventory().getContents()) {
                if (inner == null || inner.getType().isAir()) continue;
                billContentItem(blocks, contents, inner, count * inner.getAmount());
            }
            return;
        }
        org.bukkit.inventory.ItemStack template = item.clone();
        template.setAmount(1);
        contents.merge(template, count, Long::sum);
    }

    /** Add the items a paste's entities consume: the entity item (minecart/boat/armor stand/…) plus any container contents. */
    private static void addEntityMaterials(PasteMaterials needs, List<PendingEntity> entities) {
        if (entities == null || entities.isEmpty()) return;
        for (PendingEntity pe : entities) {
            if (pe.data().billableItem() == null) continue;
            needs.blocks.merge(pe.data().billableItem().getKey().toString(), 1L, Long::sum);
            for (PaperNbtHelper.ItemInstance item : pe.data().contents()) {
                billContentItem(needs.blocks, needs.contents, item.bukkit(), item.count());
            }
        }
    }

    /** Validation error carrying a protocol message key + data (aborts the whole paste). */
    private static final class PasteRejectException extends RuntimeException {
        final String key;
        final Map<String, ?> data;

        PasteRejectException(String key, Map<String, ?> data) {
            super(key);
            this.key = key;
            this.data = data;
        }
    }

    /** Refund for work never applied, mirroring {@link BillingPolicy#refund} but with scaled instant prices. */
    private static BigDecimal instantRefund(BillingPolicy policy, long planned, long applied,
                                            long replaceBreaks, BigDecimal operationPart, BigDecimal areaPart, BigDecimal blockPart) {
        long unfinished = Math.max(0, planned - applied);
        if (unfinished <= 0) return BigDecimal.ZERO.setScale(policy.fractionalDigits());
        BigDecimal placeShare = BigDecimal.ZERO;
        BigDecimal replaceShare = BigDecimal.ZERO;
        if (policy.perBlockEnabled()) {
            placeShare = blockPart.multiply(BigDecimal.valueOf(unfinished));
            long replaceUnfinished = Math.min(replaceBreaks, unfinished);
            replaceShare = blockPart.multiply(BigDecimal.valueOf(replaceUnfinished));
        }
        BigDecimal areaRefund = BigDecimal.ZERO;
        if (areaPart.signum() > 0 && planned > 0) {
            BigDecimal ratio = BigDecimal.valueOf(unfinished).divide(BigDecimal.valueOf(planned), 12, java.math.RoundingMode.HALF_UP);
            areaRefund = areaPart.multiply(ratio);
        }
        BigDecimal refund = placeShare.add(replaceShare).add(areaRefund).setScale(policy.fractionalDigits(), java.math.RoundingMode.HALF_UP);
        if (applied == 0 && operationPart.signum() > 0) {
            refund = refund.add(operationPart).setScale(policy.fractionalDigits(), java.math.RoundingMode.HALF_UP);
        }
        return refund;
    }

    /** Return materials (block items + exact container contents) for mutations never applied. */
    /**
     * Spawn the paste's entities after its blocks are placed. Item/vehicle entities are spawned and
     * their (already-taken) materials consumed; living mobs are spawned only in creative or with
     * {@code maxfastbuild.bypass.entities}, otherwise skipped. Entities that fail to spawn have their
     * materials returned.
     */
    private void spawnEntities(Player player, String world, List<PendingEntity> entities,
                               PaperInventoryHelper.RemovalLedger removals) {
        if (entities == null || entities.isEmpty()) return;
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) return;
        boolean allowMobs = player != null
                && (player.getGameMode() == GameMode.CREATIVE || player.hasPermission("maxfastbuild.bypass.entities"));
        int spawned = 0, skippedMobs = 0, cancelled = 0;
        List<PendingEntity> notSpawned = new ArrayList<>();
        for (PendingEntity pe : entities) {
            if (pe.data().mob() && !allowMobs) {
                skippedMobs++;
                notSpawned.add(pe);
                continue;
            }
            PaperEntityHelper.SpawnResult result = PaperEntityHelper.spawn(bukkitWorld, pe.data(), pe.x(), pe.y(), pe.z());
            if (result.added() && fireEntitySpawnEvent(player, result.entity(), pe.data())) {
                spawned++;
            } else {
                if (!result.added()) {
                    debugLog("paste entity spawn failed player=" + (player == null ? "?" : player.getName())
                            + " type=" + pe.data().type() + " reason=" + (result.reason() == null ? "unknown" : result.reason()));
                }
                // A cancelled event (protection plugin) removes the entity so it does not linger
                // while its materials are refunded.
                if (result.added() && result.entity() != null) {
                    result.entity().remove();
                    cancelled++;
                }
                notSpawned.add(pe);
            }
        }
        if (!notSpawned.isEmpty()) {
            returnEntityMaterials(player, notSpawned, removals);
        }
        debugLog("entities spawned=" + spawned + " skippedMobs=" + skippedMobs
                + " notSpawned=" + notSpawned.size() + " cancelled=" + cancelled);
    }

    /**
     * Fire the Bukkit event that audit plugins (CoreProtect/Prism) listen to so a pasted entity is
     * recorded as the player's action, exactly like placing it by hand. Hanging entities (item     * frames, paintings, leash knots) fire {@code HangingPlaceEvent} — the only placement event     * CoreProtect handles; minecarts/boats/armor stands fire {@code EntityPlaceEvent} (Prism     * {@code entity-place}; CoreProtect does not track those even for vanilla players); everything     * else fires {@code EntitySpawnEvent}. A cancellation removes the entity. Returns false when an     * event was fired and cancelled; true when it should count as spawned.     */
    private static boolean fireEntitySpawnEvent(Player player, org.bukkit.entity.Entity entity,
                                                PaperEntityHelper.EntityData data) {
        if (player == null || entity == null) return true;
        org.bukkit.block.Block block = entity.getWorld().getBlockAt(entity.getLocation());
        if (entity instanceof org.bukkit.entity.Hanging hanging) {
            org.bukkit.event.hanging.HangingPlaceEvent event = new org.bukkit.event.hanging.HangingPlaceEvent(
                    hanging, player, block, org.bukkit.block.BlockFace.UP, org.bukkit.inventory.EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        }
if (data.billableItem() != null) {
            org.bukkit.event.entity.EntityPlaceEvent event = new org.bukkit.event.entity.EntityPlaceEvent(
                    entity, player, block, org.bukkit.block.BlockFace.UP);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            // CoreProtect's EntityPlaceListener only logs Boat/Minecart; HangingPlaceListener
            // logs ItemFrame/Painting as block-place. Directly queue CO spawn logs for placed
            // entities that CO's listeners skip (armor stands, leash knots, …).
            if (!(entity instanceof org.bukkit.entity.Boat || entity instanceof org.bukkit.entity.Minecart)) {
                reflectCoEntitySpawnLog(player.getName(), entity.getUniqueId(), entity.getType(), entity.getLocation());
            }
            return true;
        }
        org.bukkit.event.entity.EntitySpawnEvent event = new org.bukkit.event.entity.EntitySpawnEvent(entity);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /**
     * Directly enqueue an entity spawn log to CoreProtect via reflection, bypassing event-based
     * listeners that only cover a subset of entity types ({@code EntityPlaceListener} only handles
     * Boat/Minecart; {@code HangingPlaceListener} only handles ItemFrame/Painting). This ensures
     * armor stands, leash knots, and other entities pasted via MaxFastBuild appear in CO lookups.     * No-op when CoreProtect is absent or the API method is missing.
     */
    private static void reflectCoEntitySpawnLog(String user, java.util.UUID uuid,
                                                org.bukkit.entity.EntityType type, org.bukkit.Location location) {
        try {
            Class<?> queueClass = Class.forName("net.coreprotect.consumer.Queue");
            java.lang.reflect.Method method = queueClass.getMethod(
                    "queueEntitySpawnLog", String.class, java.util.UUID.class,
                    org.bukkit.entity.EntityType.class, org.bukkit.Location.class);
            method.invoke(null, user, uuid, type, location);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    /** Give back the billable item + container contents for entities that were not spawned. */
    private void returnEntityMaterials(Player player, List<PendingEntity> entities,
                                       PaperInventoryHelper.RemovalLedger removals) {
        if (player == null || entities == null || entities.isEmpty()) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        for (PendingEntity pe : entities) {
            if (pe.data().billableItem() == null) continue;
            String key = pe.data().billableItem().getKey().toString();
            if (removals != null) removals.refundOrGive(player, key, 1);
            else {
                Material material = PaperInventoryHelper.resolveMaterial(key);
                if (material != null && !SeedCatalog.isSeeded(material)) {
                    PaperInventoryHelper.giveOrDrop(player, key, 1);
                }
            }
            for (PaperNbtHelper.ItemInstance item : pe.data().contents()) {
                if (item.bukkit() == null || item.bukkit().getType().isAir() || item.count() <= 0) continue;
                org.bukkit.inventory.ItemStack template = item.bukkit().clone();
                template.setAmount(1);
                if (removals != null) removals.refundOrGiveExact(player, template, item.count());
                else if (!SeedCatalog.isSeeded(item.bukkit().getType())) {
                    PaperInventoryHelper.giveExact(player, item.bukkit().clone(), item.count());
                }
            }
        }
    }

    private void returnUnusedMaterials(Player player, BuildPlan plan, Set<Integer> unappliedIndices,
                                       PaperInventoryHelper.RemovalLedger removals) {
        List<BlockMutation> all = plan.mutations();
        if (all.isEmpty()) return;
        if (player != null && player.getGameMode() == GameMode.CREATIVE) return;
        List<BlockMutation> unused = new ArrayList<>();
        for (int index : unappliedIndices) {
            if (index >= 0 && index < all.size()) unused.add(all.get(index));
        }
        if (unused.isEmpty()) return;
        World world = Bukkit.getWorld(plan.world());
        Object registry = world == null ? null : PaperNbtHelper.registryAccess(world);
        // With a live removal ledger (no server restart) return items to their exact source slots
        // (player inventory or nearby container); leftovers fall back to the player.
        if (removals != null) {
            for (BlockMutation mutation : unused) {
                String key = PaperInventoryHelper.itemKeyFromBlockState(mutation.targetState());
                Material blockMaterial = PaperInventoryHelper.resolveMaterial(key);
                // Fire blocks consume flint-and-steel durability (recorded under FLINT_AND_STEEL),
                // so an unapplied fire block refunds one flint use, never a fire item.
                if (blockMaterial != null && PaperInventoryHelper.isFire(blockMaterial)) {
                    removals.refundMaterial("minecraft:flint_and_steel", 1);
                } else if (player != null) {
                    removals.refundOrGive(player, key, 1);
                } else {
                    removals.refundMaterial(key, 1);
                }
                for (PaperNbtHelper.ItemInstance item : billableItems(mutation.targetState(), mutation.targetNbt(), registry)) {
                    if (item.bukkit() == null || item.bukkit().getType().isAir() || item.count() <= 0) continue;
                    org.bukkit.inventory.ItemStack template = item.bukkit().clone();
                    template.setAmount(1);
                    if (player != null) removals.refundOrGiveExact(player, template, item.count());
                    else removals.refundExact(template, item.count());
                }
            }
            return;
        }
        if (player != null) {
            for (BlockMutation mutation : unused) {
                String key = PaperInventoryHelper.itemKeyFromBlockState(mutation.targetState());
                Material material = PaperInventoryHelper.resolveMaterial(key);
                if (material != null && !SeedCatalog.isSeeded(material)) {
                    PaperInventoryHelper.giveOrDrop(player, key, 1);
                }
                for (PaperNbtHelper.ItemInstance item : billableItems(mutation.targetState(), mutation.targetNbt(), registry)) {
                    if (item.bukkit() == null || item.bukkit().getType().isAir() || item.count() <= 0) continue;
                    if (!SeedCatalog.isSeeded(item.bukkit().getType())) {
                        PaperInventoryHelper.giveExact(player, item.bukkit(), item.count());
                    }
                }
            }
            return;
        }
        // Offline: drop near the first unused mutation so nothing is silently lost.
        if (world == null) return;
        Location loc = new Location(world, unused.getFirst().position().x() + 0.5, unused.getFirst().position().y() + 0.5, unused.getFirst().position().z() + 0.5);
        for (BlockMutation mutation : unused) {
            org.bukkit.Material mat = PaperInventoryHelper.resolveMaterial(PaperInventoryHelper.itemKeyFromBlockState(mutation.targetState()));
            if (mat != null && mat.isItem()) {
                world.dropItemNaturally(loc, new org.bukkit.inventory.ItemStack(mat, 1));
            }
            for (PaperNbtHelper.ItemInstance item : billableItems(mutation.targetState(), mutation.targetNbt(), registry)) {
                if (item.bukkit() == null || item.bukkit().getType().isAir() || item.count() <= 0) continue;
                long left = item.count();
                while (left > 0) {
                    int stack = (int) Math.min(left, item.bukkit().getMaxStackSize());
                    org.bukkit.inventory.ItemStack drop = item.bukkit().clone();
                    drop.setAmount(stack);
                    world.dropItemNaturally(loc, drop);
                    left -= stack;
                }
            }
        }
    }

    /** Billable items a mutation's NBT would carry, or empty when none/undecodable (for refunds). */
    private static List<PaperNbtHelper.ItemInstance> billableItems(String targetState, String targetNbt, Object registry) {
        if (targetNbt == null || registry == null) return List.of();
        Material material;
        try {
            material = Bukkit.createBlockData(targetState).getMaterial();
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
        PaperNbtHelper.NbtCheck check = PaperNbtHelper.validateForBlock(targetNbt, material, registry);
        return check instanceof PaperNbtHelper.NbtCheck.Ok ok ? ok.items() : List.of();
    }

    /**
     * Instant paste: run the full mayMutate + mutate loop synchronously (this server tick, paid
     * multiplier). Stops at the first failure, then settles like {@link #settlePartial}: refunds
     * variable fees for unapplied work and returns unused materials. CoreProtect is recorded by the
     * {@code mutate} call path exactly as for queued tasks.
     */
    private void settleInstant(Player player, PendingPaste pending, BuildPlan plan, BillingPolicy.Charge charge,
                               String transactionId, boolean tookMoney) {
        PaperWorldAccess world = new PaperWorldAccess();
        List<BlockMutation> mutations = plan.mutations();
        long applied = 0;
        Set<Integer> unapplied = new HashSet<>();
        // Litematica-exact physics: place every block with NO_UPDATE, then one notification pass so
        // redstone computes against the final layout (see WorldAccess#beginDeferredPhysics).
        world.beginDeferredPhysics();
        try {
            for (int i = 0; i < mutations.size(); i++) {
                BlockMutation mutation = mutations.get(i);
                BlockPos pos = mutation.position();
                // Replace-by-default: a position whose current state no longer matches what the
                // schematic expects is placed over (mayMutate/mutate handle the break), never a stop.
                WorldAccess.ValidationResult validation = world.mayMutate(player.getUniqueId(), pending.world, mutation, OperationKind.PLACE);
                if (!validation.allowed()) {
                    unapplied.add(i);
                    continue;
                }
                WorldAccess.MutationResult result = world.mutate(player.getUniqueId(), pending.world, mutation, OperationKind.PLACE);
                if (!result.changed()) {
                    unapplied.add(i);
                    continue;
                }
                applied++;
                audit.record(player.getUniqueId(), player.getName(), pending.world, mutation, OperationKind.PLACE, result.breakAlreadyLogged());
            }
        } finally {
            world.endDeferredPhysics();
        }
        // Redstone settle: force re-place redstone components so onPlace fires against the final
        // layout (plain update(true,true) on unchanged blocks skips onPlace and leaves torches at
        // their partial-build state), then a cross-tick convergence tail for scheduled ticks.
        List<BlockPos> settled = null;
        if (applied > 0) {
            settled = new ArrayList<>((int) applied);
            for (int i = 0; i < mutations.size(); i++) {
                if (!unapplied.contains(i)) settled.add(mutations.get(i).position());
            }
            world.settlePlacements(pending.world, settled);
            scheduleRedstoneTail(pending.world, settled);
        }
        // Spawn pasted entities (minecarts/boats/armor stands/mobs) after the blocks are placed.
        if (!pending.entities.isEmpty()) {
            auditContainerRefunds(player.getUniqueId(), player.getName(), pending.world, pending.removals);
            spawnEntities(player, pending.world, pending.entities, pending.removals);
        }
        long planned = mutations.size();
        long missed = unapplied.size();

        BillingPolicy policy = billing();
        BigDecimal mult = instantMultiplier();
        BigDecimal operationPart = policy.perOperationEnabled() ? policy.perOperation().multiply(mult) : BigDecimal.ZERO;
        // Same area basis as the quote in finalizePastePlanning: perArea * maximumPlaneArea * multiplier.
        BigDecimal areaPart = policy.perAreaEnabled()
                ? policy.perArea().multiply(BigDecimal.valueOf(plan.bounds().maximumPlaneArea())).multiply(mult)
                : BigDecimal.ZERO;
        BigDecimal blockPart = policy.perBlockEnabled() ? policy.perBlock().multiply(mult) : BigDecimal.ZERO;
        BigDecimal refund = instantRefund(policy, planned, applied, pending.replaceBreakCount, operationPart, areaPart, blockPart);
        if (refund.signum() > 0 && charge.total().signum() > 0 && refund.compareTo(charge.total()) > 0) {
            refund = charge.total();
        }
        if (tookMoney && refund.signum() > 0) {
            String tx = transactionId + ":partial-refund";
            ledger.intent(tx, player.getUniqueId(), player.getUniqueId(), EconomyLedger.Kind.REFUND, refund);
            EconomyService.TransactionResult result = economy.deposit(player.getUniqueId(), refund, tx);
            ledger.complete(tx, player.getUniqueId(), player.getUniqueId(), EconomyLedger.Kind.REFUND, refund, result.successful(), result.message());
        }
        if (missed > 0) {
            auditContainerRefunds(player.getUniqueId(), player.getName(), pending.world, pending.removals);
            returnUnusedMaterials(player, plan, unapplied, pending.removals);
        }
        auditContainerTakes(player, pending.world, false, null, pending.removals);
        debugLog("paste executed player=" + player.getName()
                + " applied=" + applied + " planned=" + planned + " skipped=" + missed
                + " planningSkipped=" + pending.planningSkipped);
        sendProtocol(player, "completed", missed > 0 ? "maxfastbuild.task.partial" : "maxfastbuild.task.completed",
                Map.of("applied", applied, "planned", planned, "cost", charge.total().toPlainString(),
                        "refund", refund.toPlainString(), "skippedPlanning", pending.planningSkipped));
    }

    /**
     * After a paste places all blocks, run {@code paste.redstone-convergence} additional
     * notification passes over the placed positions, one per server tick, so tick-dependent
     * redstone (repeaters, pistons, observers) settles against the final layout.
     */
    private void scheduleRedstoneTail(String worldName, List<BlockPos> positions) {
        int ticks = getConfig().getInt("paste.redstone-convergence", 2);
        if (ticks <= 0 || positions == null || positions.isEmpty()) return;
        List<BlockPos> copy = new ArrayList<>(positions);
        debugLog("redstone convergence tail scheduled ticks=" + ticks + " positions=" + copy.size());
        for (int i = 1; i <= ticks; i++) {
            final int delay = i;
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!active || Bukkit.getWorld(worldName) == null) return;
                new PaperWorldAccess().settlePlacements(worldName, copy);
            }, delay);
        }
    }

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
        AuditService coreProtect;
        try {
            coreProtect = CoreProtectAuditService.discover();
        } catch (LinkageError error) {
            getLogger().info("CoreProtect API is not installed; CoreProtect audit disabled");
            coreProtect = unavailableAudit();
        }
        AuditService prism;
        try {
            prism = PrismAuditService.discover();
        } catch (LinkageError error) {
            getLogger().info("Prism API is not installed; Prism audit disabled");
            prism = unavailableAudit();
        }
        return new CompositeAuditService(List.of(coreProtect, prism));
    }

    private AuditService unavailableAudit() {
        return new AuditService() {
            public boolean available() { return false; }
            public void record(UUID playerId, String playerName, String world, BlockMutation mutation, OperationKind kind) {}
            public void record(UUID playerId, String playerName, String world, BlockMutation mutation, OperationKind kind, boolean breakAlreadyLogged) {}
        };
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
                "protocolVersion", ProtocolEnvelope.CURRENT_VERSION,
                "sessionId", session.id(),
                "secret", Base64.getUrlEncoder().withoutPadding().encodeToString(session.secret()),
                "expiresAt", session.expiresAt().toString(),
                "instantMultiplier", instantMultiplier().stripTrailingZeros().toPlainString(),
                "limits", limits())));
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

    private void sendLimitError(Player player, RequestLimitValidator.Violation violation) {
        switch (violation.kind()) {
            case AXIS -> sendProtocol(player, "error", "maxfastbuild.error.region_axis_too_large",
                    Map.of("axis", violation.axis(), "actual", violation.actual(), "limit", violation.limit()));
            case REGION_BLOCKS -> sendProtocol(player, "error", "maxfastbuild.error.region_too_large",
                    Map.of("actual", violation.actual(), "limit", violation.limit()));
            case AFFECTED_BLOCKS -> sendProtocol(player, "error", "maxfastbuild.error.affected_too_large",
                    Map.of("actual", violation.actual(), "limit", violation.limit()));
        }
    }
    private static BlockPos at(Player player) { Location p = player.getLocation(); return new BlockPos(p.getBlockX(), p.getBlockY(), p.getBlockZ()); }

    /** Accept either the traditional feet position or an explicit client-picked x y z anchor. */
    private BlockPos commandPosition(Player player, String[] args) {
        if (args.length == 1) return at(player);
        if (args.length != 4) {
            messages.send(player, "pos-usage");
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        } catch (NumberFormatException ex) {
            messages.send(player, "pos-invalid", String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            return null;
        }
    }

    /** 0 in config = unlimited (Integer.MAX_VALUE); otherwise the configured value. */
    private static int globalBudgetCap(int budget) {
        return budget <= 0 ? Integer.MAX_VALUE : budget;
    }

    private static int shapeGenerationLimit(long limit) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, limit));
    }

    private static ShapeRequest shapeRequest(Selection selection) {
        return new ShapeRequest(selection.mode(), selection.first(), selection.second(),
                selection.mode() == BuildMode.ARC ? selection.third() : null,
                selection.hollow(), selection.spacingX(), selection.spacingY(), selection.spacingZ());
    }

    private record ClientRequest(String operation, String mode, BlockPos first, BlockPos second, BlockPos third,
                                 int hollow, int spacingX, int spacingY, int spacingZ, String material) {}
    private record QueuedCommand(Selection selection, OperationKind operation, Material filter, boolean keepOnly,
                                 Set<Material> excluded) {
        QueuedCommand {
            excluded = excluded == null ? Set.of() : Set.copyOf(excluded);
        }
    }
    private record Selection(BuildMode mode, BlockPos first, BlockPos second, BlockPos third, int hollow,
                             int spacingX, int spacingY, int spacingZ, String material, String world) {
        Selection {
            if (mode != BuildMode.ARC) third = null;
        }

        Selection(BuildMode mode, BlockPos first, BlockPos second, int hollow, String material, String world) {
            this(mode, first, second, null, hollow, 1, 1, 1, material, world);
        }

        Selection withMode(BuildMode value) {
            return new Selection(value, first, second, value == BuildMode.ARC ? third : null,
                    hollow, spacingX, spacingY, spacingZ, material, world);
        }

        Selection withFirst(BlockPos value) {
            return new Selection(mode, value, second, third, hollow, spacingX, spacingY, spacingZ, material, world);
        }

        Selection withSecond(BlockPos value) {
            return new Selection(mode, first, value, third, hollow, spacingX, spacingY, spacingZ, material, world);
        }

        Selection withThird(BlockPos value) {
            return new Selection(mode, first, second, mode == BuildMode.ARC ? value : null,
                    hollow, spacingX, spacingY, spacingZ, material, world);
        }

        Selection withHollow(int value) {
            return new Selection(mode, first, second, third, value, spacingX, spacingY, spacingZ, material, world);
        }

        Selection withArraySpacing(int x, int y, int z) {
            return new Selection(mode, first, second, third, hollow, x, y, z, material, world);
        }

        Selection withMaterial(String value) {
            return new Selection(mode, first, second, third, hollow, spacingX, spacingY, spacingZ, value, world);
        }

        Selection withWorld(String value) {
            return new Selection(mode, first, second, third, hollow, spacingX, spacingY, spacingZ, material, value);
        }
    }

    private record PreviewState(String world, Set<BlockPos> positions) {}

    /** In-progress region planning for a player. Planning runs over multiple server ticks to avoid freezing the main thread. */
    private static final class PendingBuild {        final Player player;
        final OperationKind operation;
        final Selection selection;
        final long maxBlocks;
        final long maxAffectedBlocks;
        final long startedAt;
        final Material filter;
        final boolean keepOnly;
        final Set<Material> excluded;
        final List<BlockMutation> mutations = new ArrayList<>();
        long replaceBreakCount = 0;
        long totalPositions = -1;
        long processed = 0;
        boolean failed = false;
        String failureReason = null;
        Set<BlockPos> positions;
        Iterator<BlockPos> iterator;
        boolean notified = false;

        PendingBuild(Player player, Selection selection, OperationKind operation, long maxBlocks,
                     long maxAffectedBlocks) {
            this(player, selection, operation, maxBlocks, maxAffectedBlocks, null, false, Set.of());
        }

        PendingBuild(Player player, Selection selection, OperationKind operation, long maxBlocks,
                     long maxAffectedBlocks, Material filter, boolean keepOnly, Set<Material> excluded) {
            this.player = player;
            this.selection = selection;
            this.operation = operation;
            this.maxBlocks = maxBlocks;
            this.maxAffectedBlocks = maxAffectedBlocks;
            this.filter = filter;
            this.keepOnly = keepOnly;
            this.excluded = excluded == null ? Set.of() : Set.copyOf(excluded);
            this.startedAt = System.currentTimeMillis();
        }
    }

    /** Absolute position and its palette target state (client-supplied, re-validated per tick). */
    private record PastePos(BlockPos position, String targetState, String targetNbt) {}

    private record PasteRegionMetrics(Bounds bounds, long volume, List<PasteTransfer.Region> regions) {
        long sizeX() { return bounds.sizeX(); }
        long sizeY() { return bounds.sizeY(); }
        long sizeZ() { return bounds.sizeZ(); }

        boolean contains(BlockPos position) {
            for (PasteTransfer.Region region : regions) {
                if (position.x() >= region.minX() && position.x() <= region.maxX()
                        && position.y() >= region.minY() && position.y() <= region.maxY()
                        && position.z() >= region.minZ() && position.z() <= region.maxZ()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A validated pasted entity and its absolute spawn position. */
    private record PendingEntity(PaperEntityHelper.EntityData data, double x, double y, double z) {}

    /**
     * A precheck problem with one pasted block/entity/item. {@code fatal} marks an unexpected
     * server error (e.g. a missing NMS class on this server version), which cancels the whole
     * paste; non-fatal entries are expected rejections/skips that are reported but do not stop it.
     */
    private record PastePrecheckIssue(String kind, String target, String detail, boolean fatal) {}

    /** In-progress validation of an assembled paste, ticked like a {@link PendingBuild}. */
    private static final class PendingPaste {
        final Player player;
        final String world;
        final boolean instant;
        final Iterator<PastePos> iterator;
        final List<BlockMutation> mutations = new ArrayList<>();
        final List<PendingEntity> entities;
        final Bounds regionBounds;
        final long regionBlocks;
        final long maxAffectedBlocks;
        final List<PastePrecheckIssue> issues = new ArrayList<>();
        PaperInventoryHelper.RemovalLedger removals;
        PasteMaterials needs;
        long replaceBreakCount = 0;
        long processed = 0;
        long planningSkipped = 0;

        PendingPaste(Player player, String world, boolean instant, List<PastePos> positions,
                     List<PendingEntity> entities, Bounds regionBounds, long regionBlocks,
                     long maxAffectedBlocks) {
            this.player = player;
            this.world = world;
            this.instant = instant;
            this.iterator = positions.iterator();
            this.entities = entities == null ? List.of() : List.copyOf(entities);
            this.regionBounds = regionBounds;
            this.regionBlocks = regionBlocks;
            this.maxAffectedBlocks = maxAffectedBlocks;
        }
    }
}
