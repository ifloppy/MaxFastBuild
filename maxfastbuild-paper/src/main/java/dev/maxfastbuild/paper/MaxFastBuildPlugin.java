package dev.maxfastbuild.paper;

import com.google.gson.Gson;
import dev.maxfastbuild.api.*;
import dev.maxfastbuild.core.billing.BillingPolicy;
import dev.maxfastbuild.core.limit.TokenBucket;
import dev.maxfastbuild.core.protocol.*;
import dev.maxfastbuild.core.shape.DefaultShapeGenerator;
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

    @Override public void onEnable() {
        saveDefaultConfig();
        protocol = new SecureProtocol(Clock.systemUTC(), Duration.ofMinutes(getConfig().getLong("protocol.session-minutes", 30)), getConfig().getInt("protocol.max-payload-bytes", 16384));
        database = new SqliteDatabase(getDataFolder().toPath().resolve("maxfastbuild.db"));
        tasks = new SqliteTaskRepository(database);
        ledger = new EconomyLedger(database);
        tasks.initialize(); ledger.initialize();
        audit = safeDiscoverAudit();
        economy = safeDiscoverEconomy();
        executor = new TaskExecutor(tasks, new PaperWorldAccess(), audit, Clock.systemUTC());
        validateIntegrations();

        PublicCommand publicCommand = new PublicCommand(this);
        PluginCommand mfb = Objects.requireNonNull(getCommand("mfb"));
        mfb.setExecutor(publicCommand); mfb.setTabCompleter(publicCommand);
        Objects.requireNonNull(getCommand("mfbadmin")).setExecutor((sender, command, label, args) -> handleAdmin(sender, args));
        getServer().getPluginManager().registerEvents(this, this);
        resumeTasks();
        long period = Math.max(1, getConfig().getLong("execution.ticks-per-block", 1));
        getServer().getScheduler().runTaskTimer(this, this::tickTasks, period, period);
    }

    @Override public void onDisable() {
        if (tasks != null) {
            for (BuildTask task : tasks.recoverable()) {
                if (task.status() == TaskStatus.RUNNING || task.status() == TaskStatus.QUEUED)
                    tasks.save(task.transition(TaskStatus.PAUSED_SHUTDOWN, Instant.now()));
            }
            tasks.close();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInternalCommand(PlayerCommandPreprocessEvent event) {
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
                    if (!rateLimit(player)) {
                        sendProtocol(player, "error", "maxfastbuild.error.rate_limited", Map.of());
                        return;
                    }
                    CompactPlaceCommand.Intent intent = CompactPlaceCommand.parse(message);
                    handleClientRequest(player, new ClientRequest("place", intent.mode().name().toLowerCase(Locale.ROOT),
                            intent.first(), intent.second(), intent.hollow(), intent.material()));
                }
                case "break" -> {
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
        for (BuildTask task : tasks.recoverable()) if (task.playerId().equals(event.getPlayer().getUniqueId()) && task.status() == TaskStatus.RUNNING)
            tasks.save(task.transition(TaskStatus.PAUSED_OFFLINE, Instant.now()));
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        for (BuildTask task : tasks.recoverable()) if (task.playerId().equals(event.getPlayer().getUniqueId()) && task.status() == TaskStatus.PAUSED_OFFLINE)
            executor.enqueue(task.transition(TaskStatus.QUEUED, Instant.now()));
    }

    void handlePublicCommand(Player player, String[] args) {
        if (args.length == 0) { player.sendMessage(Component.text("/mfb mode|pos1|pos2|apply|cancel|undo|redo|status|language")); return; }
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
            default -> player.sendMessage(Component.text("This command is available through the client UI or a later command phase."));
        }
    }

    private void handleClientRequest(Player player, ClientRequest request) {
        BuildMode mode = BuildMode.valueOf(request.mode().toUpperCase(Locale.ROOT));
        Selection selection = new Selection(mode, request.first(), request.second(), request.hollow(), request.material());
        selections.put(player.getUniqueId(), selection);
        submit(player, selection, OperationKind.valueOf(request.operation().toUpperCase(Locale.ROOT)));
    }

    private void submit(Player player, Selection selection, OperationKind operation) {
        if (selection.first() == null || selection.second() == null) { sendProtocol(player, "error", "maxfastbuild.error.positions_required", Map.of()); return; }
        if (getConfig().getBoolean("coreprotect.required", true) && !audit.available()) { sendProtocol(player, "error", "maxfastbuild.error.coreprotect_unavailable", Map.of()); return; }
        if (getConfig().getBoolean("economy.enabled") && !economy.enabled() && !player.hasPermission("maxfastbuild.bypass.cost")) { sendProtocol(player, "error", "maxfastbuild.error.economy_unavailable", Map.of()); return; }
        int max = getConfig().getInt("execution.max-region-blocks", 100000);
        if (tasks.activeCount(player.getUniqueId()) >= getConfig().getInt("execution.max-concurrent-tasks-per-player", 2)) { sendProtocol(player, "error", "maxfastbuild.error.task_limit", Map.of()); return; }
        Set<BlockPos> positions = new DefaultShapeGenerator().generate(new ShapeRequest(selection.mode(), selection.first(), selection.second(), selection.hollow()), max);
        PaperWorldAccess world = new PaperWorldAccess();
        List<BlockMutation> mutations = new ArrayList<>();
        for (BlockPos pos : positions) {
            String before = world.stateAt(player.getWorld().getName(), pos);
            if (operation == OperationKind.BREAK && before.equals("minecraft:air")) continue;
            BlockMutation mutation = new BlockMutation(pos, before, operation == OperationKind.BREAK ? "minecraft:air" : selection.material());
            WorldAccess.ValidationResult validation = world.mayMutate(player.getUniqueId(), player.getWorld().getName(), mutation, operation);
            if (!validation.allowed()) {
                if ("insufficient_tool".equals(validation.reason())) {
                    sendProtocol(player, "error", "maxfastbuild.error.insufficient_tool", Map.of("reason", validation.reason()));
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
            // Every planned block must be breakable with some inventory tool (before charging).
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
        // Config flag alone enables shulker contents; optional extra permission gate.
        boolean searchShulkers = getConfig().getBoolean("inventory.search-shulker-boxes", false);
        if (searchShulkers && getConfig().getBoolean("inventory.require-shulker-permission", false)
                && !player.hasPermission("maxfastbuild.material.shulker")) {
            searchShulkers = false;
        }
        String itemKey = PaperInventoryHelper.itemKeyFromBlockState(selection.material());
        long need = mutations.size();
        if (requireMaterials) {
            if (PaperInventoryHelper.resolveMaterial(itemKey) == null) {
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
                if (removed > 0) {
                    org.bukkit.Material mat = PaperInventoryHelper.resolveMaterial(itemKey);
                    if (mat != null) player.getInventory().addItem(new org.bukkit.inventory.ItemStack(mat, (int) removed));
                }
                if (tookMoney) refundMoney(player, taskId, charge.total(), transactionId);
                sendProtocol(player, "error", "maxfastbuild.error.insufficient_materials",
                        Map.of("need", need, "have", removed, "material", itemKey));
                return;
            }
            tookItems = true;
        }

        Instant now = Instant.now();
        BuildTask task = new BuildTask(taskId, player.getUniqueId(), player.getName(), plan, TaskStatus.QUEUED, 0, null, charge.total(), BigDecimal.ZERO, now, now, null);
        try {
            executor.enqueue(task);
        } catch (RuntimeException ex) {
            if (tookItems) {
                org.bukkit.Material mat = PaperInventoryHelper.resolveMaterial(itemKey);
                if (mat != null) player.getInventory().addItem(new org.bukkit.inventory.ItemStack(mat, (int) need));
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
        int count = Math.max(1, getConfig().getInt("execution.blocks-per-step", 1));
        for (BuildTask task : tasks.recoverable()) {
            if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.RUNNING) continue;
            if (Bukkit.getPlayer(task.playerId()) == null) continue;
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
     */
    private void settlePartial(TaskExecutor.TickResult result) {
        BuildTask task = result.task();
        long planned = task.plan().mutations().size();
        long appliedCount = result.totalApplied();
        long missed = Math.max(0, planned - appliedCount);

        BillingPolicy policy = billing();
        BigDecimal areaPart = policy.perAreaEnabled()
                ? policy.perArea().multiply(BigDecimal.valueOf(task.plan().bounds().maximumPlaneArea()))
                : BigDecimal.ZERO;
        BigDecimal blockPart = policy.perBlockEnabled()
                ? policy.perBlock().multiply(BigDecimal.valueOf(planned))
                : BigDecimal.ZERO;
        // If never started (0 applied) and still only queued conceptually, also refund operation fee.
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
            tasks.save(new BuildTask(task.id(), task.playerId(), task.playerName(), task.plan(), task.status(),
                    task.cursor(), task.escrowId(), task.charged(), task.refunded().add(refund),
                    task.createdAt(), Instant.now(), task.failure()));
        }

        if (task.plan().operation() == OperationKind.PLACE && missed > 0 && player != null
                && player.getGameMode() != GameMode.CREATIVE) {
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
        String itemKey = PaperInventoryHelper.itemKeyFromBlockState(task.plan().mutations().getFirst().targetState());
        org.bukkit.Material mat = PaperInventoryHelper.resolveMaterial(itemKey);
        if (mat == null || !mat.isItem()) return;
        long left = count;
        while (left > 0) {
            int stack = (int) Math.min(left, mat.getMaxStackSize());
            player.getInventory().addItem(new org.bukkit.inventory.ItemStack(mat, stack));
            left -= stack;
        }
    }

    private void resumeTasks() {
        for (BuildTask task : tasks.recoverable()) {
            TaskStatus status = task.status();
            if ((status == TaskStatus.RUNNING || status == TaskStatus.PAUSED_SHUTDOWN) && Bukkit.getPlayer(task.playerId()) != null)
                executor.enqueue(task.transition(TaskStatus.QUEUED, Instant.now()));
        }
    }

    private boolean handleAdmin(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("maxfastbuild.admin")) return true;
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) { reloadConfig(); sender.sendMessage("MaxFastBuild configuration reloaded"); }
        else if (args.length > 0 && args[0].equalsIgnoreCase("recovery")) sender.sendMessage("Recoverable tasks: " + tasks.recoverable().size() + ", pending ledger entries: " + ledger.pending().size());
        else sender.sendMessage("/mfbadmin reload|recovery");
        return true;
    }

    private void cancelPlayerTasks(Player player) {
        for (BuildTask task : tasks.recoverable()) {
            if (!task.playerId().equals(player.getUniqueId())) continue;
            if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.RUNNING) continue;
            try {
                TaskExecutor.TickResult aborted = executor.abort(task.id());
                settlePartial(aborted);
            } catch (RuntimeException ex) {
                // Not in executor memory (e.g. recovered only in DB): full miss refund using charged total block/area.
                try {
                    BuildTask cancelling = task.transition(TaskStatus.CANCELLING, Instant.now());
                    tasks.save(cancelling);
                    int appliedGuess = Math.min(task.cursor(), task.plan().mutations().size());
                    settlePartial(new TaskExecutor.TickResult(
                            cancelling.transition(TaskStatus.CANCELLED, Instant.now()), 0, 0, appliedGuess, true));
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
        return new EconomyService() {
            public TransactionResult withdraw(UUID id, BigDecimal amount, String tx) { return new TransactionResult(false, "economy_unavailable"); }
            public TransactionResult deposit(UUID id, BigDecimal amount, String tx) { return new TransactionResult(false, "economy_unavailable"); }
            public boolean enabled() { return false; }
        };
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
        SecureProtocol.Session session = protocol.issue(player.getUniqueId());
        sessions.put(player.getUniqueId(), session);
        sendMarked(player, GSON.toJson(Map.of("mfb", 1, "type", "hello", "sessionId", session.id(), "secret", Base64.getUrlEncoder().withoutPadding().encodeToString(session.secret()), "expiresAt", session.expiresAt().toString())));
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
