package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.BlockMutation;
import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.OperationKind;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MfbEventSelfTestPlugin extends JavaPlugin {
    private Method placementView;
    private Object worldAccess;
    private Method settlePlacements;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTask(this, this::runTests);
    }

    private void runTests() {
        try {
            Class<?> worldAccessClass = Class.forName("dev.maxfastbuild.paper.PaperWorldAccess");
            placementView = worldAccessClass.getDeclaredMethod("placementView", Block.class, BlockData.class);
            placementView.setAccessible(true);
            Constructor<?> ctor = worldAccessClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            worldAccess = ctor.newInstance();
            settlePlacements = worldAccessClass.getMethod("settlePlacements", String.class, List.class);
            settlePlacements.setAccessible(true);

            runNkvdTntTest();
            runCoreProtectDedupTest();
            runCoreProtectReplacementDedupTest();
            runPrismBreakDedupProbe();
            runMachineEquivalenceTest();
        } catch (Throwable t) {
            fail("bootstrap", t);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void runNkvdTntTest() throws Exception {
        World world = Bukkit.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        int baseX = spawn.getBlockX() + 48;
        int baseZ = spawn.getBlockZ() + 48;
        int baseY = Math.min(world.getMaxHeight() - 8, world.getHighestBlockYAt(baseX, baseZ) + 6);

        UUID actorId = UUID.fromString("a8dbb776-ec27-4d4d-a5d7-7923c14d5a41");
        Player actor = fakePlayer(actorId, world, new Location(world, baseX + 0.5, baseY, baseZ + 0.5));
        BlockData tntData = Material.TNT.createBlockData();
        int events = 0;
        int cancelled = 0;

        for (int i = 0; i < 8; i++) {
            Block real = world.getBlockAt(baseX + i, baseY, baseZ);
            if (!real.getType().isAir()) continue;
            Block virtualPlaced = virtualPlaced(real, tntData);
            if (virtualPlaced.getType() != Material.TNT || real.getType() != Material.AIR) {
                throw new IllegalStateException("virtual TNT mismatch at " + real.getLocation());
            }
            BlockPlaceEvent event = placementEvent(virtualPlaced, real, actor, Material.TNT);
            Bukkit.getPluginManager().callEvent(event);
            events++;
            if (event.isCancelled() || !event.canBuild()) cancelled++;
            getLogger().info("TNT SELFTEST event=" + events + " virtual=" + virtualPlaced.getType()
                    + " real=" + real.getType() + " cancelled=" + event.isCancelled());
            if (real.getType() != Material.AIR) {
                throw new IllegalStateException("TNT event mutated real world at " + real.getLocation());
            }
        }

        getLogger().info("TNT SELFTEST RESULT events=" + events + " cancelled=" + cancelled + " realWorldChanges=0");
        if (events < 6) throw new IllegalStateException("not enough air cells for TNT burst test");
        if (cancelled == 0) throw new IllegalStateException("NKVD did not cancel any MFB-style TNT placement");
        getLogger().info("TNT SELFTEST PASS: NKVD observed/cancelled MFB-style TNT placement without world mutation");
    }

    private void runCoreProtectDedupTest() throws Exception {
        World world = Bukkit.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX() + 68;
        int z = spawn.getBlockZ() + 68;
        int y = Math.min(world.getMaxHeight() - 8, world.getHighestBlockYAt(x, z) + 6);
        Block real = world.getBlockAt(x, y, z);
        real.setType(Material.AIR, false);

        CoreProtectAPI api = CoreProtect.getInstance().getAPI();
        int before = countCoreProtect(api.queueLookup(real), api, "mfb-selftest");
        Player actor = fakePlayer(UUID.fromString("72b923bd-95da-4824-a433-ab5541375225"), world,
                new Location(world, x + 0.5, y, z + 0.5));
        BlockData stone = Material.STONE.createBlockData();
        Block virtualPlaced = virtualPlaced(real, stone);
        BlockPlaceEvent event = placementEvent(virtualPlaced, real, actor, Material.STONE);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            throw new IllegalStateException("CoreProtect selftest placement unexpectedly cancelled");
        }
        if (!real.getType().isAir()) {
            throw new IllegalStateException("CoreProtect event mutated real world before MFB mutation");
        }

        real.setBlockData(stone, false);
        BlockMutation mutation = new BlockMutation(new BlockPos(x, y, z), "minecraft:air", "minecraft:stone");
        Object mfbPlugin = Bukkit.getPluginManager().getPlugin("MaxFastBuild");
        if (mfbPlugin == null) throw new IllegalStateException("MaxFastBuild plugin unavailable in selftest");
        Class<?> auditClass = Class.forName("dev.maxfastbuild.paper.CoreProtectAuditService", true,
                mfbPlugin.getClass().getClassLoader());
        Method discoverAudit = auditClass.getDeclaredMethod("discover");
        discoverAudit.setAccessible(true);
        Object audit = discoverAudit.invoke(null);
        Method availableAudit = auditClass.getDeclaredMethod("available");
        availableAudit.setAccessible(true);
        if (!((Boolean) availableAudit.invoke(audit))) {
            throw new IllegalStateException("CoreProtect audit unavailable in selftest");
        }
        Method recordAudit = auditClass.getDeclaredMethod("record", UUID.class, String.class, String.class,
                BlockMutation.class, OperationKind.class, boolean.class, boolean.class);
        recordAudit.setAccessible(true);
        recordAudit.invoke(audit, actor.getUniqueId(), actor.getName(), world.getName(), mutation,
                OperationKind.PLACE, false, true);

        int after = countCoreProtect(api.queueLookup(real), api, "mfb-selftest");
        int delta = after - before;
        getLogger().info("COREPROTECT SELFTEST queuedBefore=" + before + " queuedAfter=" + after + " delta=" + delta);
        if (delta != 1) {
            throw new IllegalStateException("expected exactly one CoreProtect placement queue record, got delta=" + delta);
        }
        getLogger().info("COREPROTECT SELFTEST PASS: placement event + MFB audit dedup produced exactly one record");
        real.setType(Material.AIR, false);
    }

    private void runCoreProtectReplacementDedupTest() throws Exception {
        World world = Bukkit.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX() + 72;
        int z = spawn.getBlockZ() + 72;
        int y = Math.min(world.getMaxHeight() - 8, world.getHighestBlockYAt(x, z) + 6);
        Block real = world.getBlockAt(x, y, z);
        BlockData stone = Material.STONE.createBlockData();
        BlockData target = Material.COBBLESTONE.createBlockData();
        real.setBlockData(stone, false);

        CoreProtectAPI api = CoreProtect.getInstance().getAPI();
        String actorName = "mfb-selftest";
        int removalsBefore = countCoreProtectAction(api.queueLookup(real), api, actorName, 0);
        int placementsBefore = countCoreProtectAction(api.queueLookup(real), api, actorName, 1);
        Player actor = fakePlayer(UUID.fromString("72b923bd-95da-4824-a433-ab5541375225"), world,
                new Location(world, x + 0.5, y, z + 0.5));

        // A solid MFB replacement represents two vanilla actions: break STONE, then place
        // COBBLESTONE into AIR. Feed that exact placement semantics through CoreProtect's real
        // BlockPlaceEvent listener. This specifically catches the old bug where replacedState=STONE
        // made CoreProtect add a second removal in addition to the break record.
        Block virtualPlaced = virtualPlaced(real, target);
        org.bukkit.block.BlockState airState = real.getState();
        airState.setBlockData(Material.AIR.createBlockData());
        BlockPlaceEvent placeEvent = new BlockPlaceEvent(virtualPlaced, airState, real.getRelative(BlockFace.DOWN),
                new ItemStack(Material.COBBLESTONE), actor, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(placeEvent);
        if (placeEvent.isCancelled() || !placeEvent.canBuild()) {
            throw new IllegalStateException("CoreProtect replacement selftest placement unexpectedly cancelled");
        }
        if (real.getType() != Material.STONE) {
            throw new IllegalStateException("replacement placement event mutated the real block before break");
        }

        // Stand in for the removal CoreProtect receives from Player#breakBlock -> BlockBreakEvent.
        if (!api.logRemoval(actorName, real.getLocation(), Material.STONE, stone)) {
            throw new IllegalStateException("CoreProtect replacement selftest could not seed break-side removal");
        }
        real.setType(Material.AIR, false);
        real.setBlockData(target, false);

        BlockMutation mutation = new BlockMutation(new BlockPos(x, y, z), "minecraft:stone", "minecraft:cobblestone");
        Object mfbPlugin = Bukkit.getPluginManager().getPlugin("MaxFastBuild");
        if (mfbPlugin == null) throw new IllegalStateException("MaxFastBuild plugin unavailable in replacement selftest");
        Class<?> auditClass = Class.forName("dev.maxfastbuild.paper.CoreProtectAuditService", true,
                mfbPlugin.getClass().getClassLoader());
        Method discoverAudit = auditClass.getDeclaredMethod("discover");
        discoverAudit.setAccessible(true);
        Object audit = discoverAudit.invoke(null);
        Method recordAudit = auditClass.getDeclaredMethod("record", UUID.class, String.class, String.class,
                BlockMutation.class, OperationKind.class, boolean.class, boolean.class);
        recordAudit.setAccessible(true);
        recordAudit.invoke(audit, actor.getUniqueId(), actorName, world.getName(), mutation,
                OperationKind.PLACE, true, true);

        int removalsAfter = countCoreProtectAction(api.queueLookup(real), api, actorName, 0);
        int placementsAfter = countCoreProtectAction(api.queueLookup(real), api, actorName, 1);
        int removalDelta = removalsAfter - removalsBefore;
        int placementDelta = placementsAfter - placementsBefore;
        getLogger().info("COREPROTECT REPLACE SELFTEST removalDelta=" + removalDelta
                + " placementDelta=" + placementDelta);
        if (removalDelta != 1 || placementDelta != 1) {
            throw new IllegalStateException("expected one removal + one placement for replacement, got removalDelta="
                    + removalDelta + " placementDelta=" + placementDelta);
        }
        getLogger().info("COREPROTECT REPLACE SELFTEST PASS: replacement produced exactly one removal + one placement");
        real.setType(Material.AIR, false);
    }

    private void runPrismBreakDedupProbe() throws Exception {
        var prism = Bukkit.getPluginManager().getPlugin("prism");
        if (prism == null || !prism.isEnabled()) {
            getLogger().info("PRISM BREAK SELFTEST SKIP: Prism is not enabled");
            return;
        }

        World world = Bukkit.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX() + 76;
        int z = spawn.getBlockZ() + 76;
        int y = Math.min(world.getMaxHeight() - 8, world.getHighestBlockYAt(x, z) + 6);
        Block real = world.getBlockAt(x, y, z);
        real.setType(Material.STONE, false);

        Player actor = fakePlayer(UUID.fromString("72b923bd-95da-4824-a433-ab5541375225"), world,
                new Location(world, x + 0.5, y, z + 0.5));
        BlockBreakEvent breakEvent = new BlockBreakEvent(real, actor);
        Bukkit.getPluginManager().callEvent(breakEvent);
        if (breakEvent.isCancelled()) {
            throw new IllegalStateException("Prism break selftest event unexpectedly cancelled");
        }
        real.setType(Material.AIR, false);

        BlockMutation mutation = new BlockMutation(new BlockPos(x, y, z), "minecraft:stone", "minecraft:air");
        Object mfbPlugin = Bukkit.getPluginManager().getPlugin("MaxFastBuild");
        if (mfbPlugin == null) throw new IllegalStateException("MaxFastBuild plugin unavailable in Prism break selftest");
        Class<?> auditClass = Class.forName("dev.maxfastbuild.paper.PrismAuditService", true,
                mfbPlugin.getClass().getClassLoader());
        Method discoverAudit = auditClass.getDeclaredMethod("discover");
        discoverAudit.setAccessible(true);
        Object audit = discoverAudit.invoke(null);
        Method availableAudit = auditClass.getDeclaredMethod("available");
        availableAudit.setAccessible(true);
        if (!((Boolean) availableAudit.invoke(audit))) {
            throw new IllegalStateException("Prism audit unavailable in break selftest");
        }
        Method recordAudit = auditClass.getDeclaredMethod("record", UUID.class, String.class, String.class,
                BlockMutation.class, OperationKind.class, boolean.class, boolean.class);
        recordAudit.setAccessible(true);
        // The BlockBreakEvent above is the normal player-event path. MFB's API audit must therefore
        // remain silent when breakAlreadyLogged=true, otherwise Prism gets two break records.
        recordAudit.invoke(audit, actor.getUniqueId(), actor.getName(), world.getName(), mutation,
                OperationKind.BREAK, true, false);
        getLogger().info("PRISM BREAK SELFTEST PROBE: event + MFB dedup submitted at " + x + "," + y + "," + z
                + "; database must contain exactly one block-break");
    }

    private static int countCoreProtectAction(List<String[]> rows, CoreProtectAPI api, String playerName, int actionId) {
        if (rows == null) return 0;
        int count = 0;
        for (String[] row : rows) {
            CoreProtectAPI.ParseResult parsed = api.parseResult(row);
            if (playerName.equals(parsed.getPlayer()) && parsed.getActionId() == actionId) count++;
        }
        return count;
    }

    private static int countCoreProtect(List<String[]> rows, CoreProtectAPI api, String playerName) {
        if (rows == null) return 0;
        int count = 0;
        for (String[] row : rows) {
            CoreProtectAPI.ParseResult parsed = api.parseResult(row);
            if (playerName.equals(parsed.getPlayer()) && parsed.getActionId() == 1) count++;
        }
        return count;
    }

    private void runMachineEquivalenceTest() throws Exception {
        World world = Bukkit.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        int terrainY = world.getHighestBlockYAt(spawn.getBlockX() + 80, spawn.getBlockZ() + 80);
        int y = Math.min(world.getMaxHeight() - 12, terrainY + 8);
        Origin baseline = new Origin(spawn.getBlockX() + 80, y, spawn.getBlockZ() + 80);
        Origin evented = new Origin(spawn.getBlockX() + 104, y, spawn.getBlockZ() + 80);
        clearBox(world, baseline);
        clearBox(world, evented);

        List<Cell> machine = machineTemplate();
        Player actor = fakePlayer(UUID.fromString("569858dd-5e4c-4956-989e-782b14ba4c93"), world,
                new Location(world, evented.x + 0.5, evented.y, evented.z + 0.5));

        List<Object> baselinePositions = new ArrayList<>();
        List<Object> eventedPositions = new ArrayList<>();
        for (Cell cell : machine) {
            Block a = block(world, baseline, cell);
            Block b = block(world, evented, cell);
            a.setBlockData(cell.data.clone(), false);

            Block virtualPlaced = virtualPlaced(b, cell.data);
            BlockPlaceEvent event = placementEvent(virtualPlaced, b, actor, cell.data.getMaterial());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || !event.canBuild()) {
                throw new IllegalStateException("ordinary machine block event cancelled: " + cell.data.getMaterial());
            }
            if (!b.getType().isAir()) {
                throw new IllegalStateException("machine event changed world before setBlockData: " + b.getLocation());
            }
            b.setBlockData(cell.data.clone(), false);

            baselinePositions.add(blockPos(a.getX(), a.getY(), a.getZ()));
            eventedPositions.add(blockPos(b.getX(), b.getY(), b.getZ()));
        }

        // Exact MFB sequence: one settle after the bulk NO_UPDATE placement, then two convergence tails.
        settle(world, baselinePositions);
        settle(world, eventedPositions);
        Bukkit.getScheduler().runTaskLater(this, () -> settleUnchecked(world, baselinePositions, eventedPositions), 1L);
        Bukkit.getScheduler().runTaskLater(this, () -> settleUnchecked(world, baselinePositions, eventedPositions), 2L);
        Bukkit.getScheduler().runTaskLater(this, () -> finishMachineComparison(world, baseline, evented), 5L);
    }

    private void finishMachineComparison(World world, Origin baseline, Origin evented) {
        try {
            Map<String, String> left = snapshotBox(world, baseline);
            Map<String, String> right = snapshotBox(world, evented);
            if (!left.equals(right)) {
                getLogger().severe("MACHINE SELFTEST mismatch baseline=" + left + " evented=" + right);
                throw new IllegalStateException("event-injected paste diverged from baseline deferred-physics paste");
            }
            getLogger().info("MACHINE SELFTEST PASS: baseline and event-injected deferred-physics machines are identical ("
                    + left.size() + " cells compared after convergence)");
            getLogger().info("SELFTEST PASS: player-style placement events preserve current machine paste convergence path");
        } catch (Throwable t) {
            fail("machine comparison", t);
        } finally {
            clearBox(world, baseline);
            clearBox(world, evented);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private List<Cell> machineTemplate() {
        Directional piston = (Directional) Material.STICKY_PISTON.createBlockData();
        piston.setFacing(BlockFace.EAST);
        Directional repeater = (Directional) Material.REPEATER.createBlockData();
        repeater.setFacing(BlockFace.EAST);
        return List.of(
                new Cell(1, 0, 0, piston),
                new Cell(2, 0, 0, Material.SLIME_BLOCK.createBlockData()),
                new Cell(0, 0, 0, Material.REDSTONE_BLOCK.createBlockData()),
                new Cell(0, 0, 3, Material.REDSTONE_LAMP.createBlockData()),
                new Cell(0, 0, 4, Material.REDSTONE_BLOCK.createBlockData()),
                new Cell(4, 0, 0, Material.STONE.createBlockData()),
                new Cell(4, 1, 0, Material.REDSTONE_TORCH.createBlockData()),
                new Cell(6, 0, 0, Material.STONE.createBlockData()),
                new Cell(6, 1, 0, repeater),
                new Cell(5, 1, 0, Material.REDSTONE_BLOCK.createBlockData())
        );
    }

    private Block virtualPlaced(Block real, BlockData data) throws Exception {
        return (Block) placementView.invoke(null, real, data);
    }

    private static BlockPlaceEvent placementEvent(Block virtualPlaced, Block real, Player actor, Material material) {
        return new BlockPlaceEvent(virtualPlaced, real.getState(), real.getRelative(BlockFace.DOWN),
                new ItemStack(material), actor, true, EquipmentSlot.HAND);
    }

    private void settle(World world, List<Object> positions) throws Exception {
        settlePlacements.invoke(worldAccess, world.getName(), positions);
    }

    private void settleUnchecked(World world, List<Object> a, List<Object> b) {
        try {
            settle(world, a);
            settle(world, b);
        } catch (Throwable t) {
            fail("convergence settle", t);
        }
    }

    private static Object blockPos(int x, int y, int z) throws Exception {
        Class<?> type = Class.forName("dev.maxfastbuild.api.BlockPos");
        return type.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
    }

    private static Block block(World world, Origin origin, Cell cell) {
        return world.getBlockAt(origin.x + cell.dx, origin.y + cell.dy, origin.z + cell.dz);
    }

    private static Map<String, String> snapshotBox(World world, Origin origin) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int y = 0; y <= 2; y++) {
            for (int z = -1; z <= 5; z++) {
                for (int x = -1; x <= 8; x++) {
                    Block block = world.getBlockAt(origin.x + x, origin.y + y, origin.z + z);
                    result.put(x + "," + y + "," + z, block.getBlockData().getAsString());
                }
            }
        }
        return result;
    }

    private static void clearBox(World world, Origin origin) {
        for (int y = -1; y <= 3; y++) {
            for (int z = -2; z <= 6; z++) {
                for (int x = -2; x <= 9; x++) {
                    world.getBlockAt(origin.x + x, origin.y + y, origin.z + z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void fail(String stage, Throwable t) {
        getLogger().severe("SELFTEST FAIL at " + stage + ": " + t);
        t.printStackTrace();
    }

    private static Player fakePlayer(UUID uuid, World world, Location location) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "mfb-selftest";
                    case "getWorld" -> world;
                    case "getLocation" -> location.clone();
                    case "getGameMode" -> GameMode.SURVIVAL;
                    case "hasPermission", "isPermissionSet", "isOp", "isOnline", "isValid", "isDead" -> false;
                    case "sendMessage", "sendActionBar" -> null;
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "MfbSelfTestPlayer{" + uuid + "}";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private record Origin(int x, int y, int z) {}
    private record Cell(int dx, int dy, int dz, BlockData data) {}
}
