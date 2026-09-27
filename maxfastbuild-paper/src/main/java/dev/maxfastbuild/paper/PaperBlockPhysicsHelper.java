package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Replays the vanilla callbacks omitted by Paper's bulk no-physics block writes. */
final class PaperBlockPhysicsHelper {
    private static final AtomicBoolean FALLBACK_LOGGED = new AtomicBoolean();
    private static volatile NmsAccess cachedAccess;
    private static volatile ReflectiveOperationException accessFailure;

    private PaperBlockPhysicsHelper() {}

    /**
     * Replays the side effects of a normal block state transition after all paste blocks exist.
     * Bukkit's {@code BlockState.update(true, true)} is not sufficient here: it writes the exact
     * state already in the chunk, and Minecraft returns early for that no-op before running
     * {@code onPlace} or neighbor shape updates.
     */
    static void replayPlacement(World world, BlockPos position, BlockData previousData) {
        try {
            NmsAccess access = access(world);
            Object level = invoke(access.getHandle, world);
            Object nmsPosition = access.blockPosConstructor.newInstance(position.x(), position.y(), position.z());
            Block block = world.getBlockAt(position.x(), position.y(), position.z());
            Object oldState = invoke(access.blockDataGetState, previousData);
            Object newState = invoke(access.blockDataGetState, block.getBlockData());
            Object oldBlock = invoke(access.stateGetBlock, oldState);
            Object newBlock = invoke(access.stateGetBlock, newState);

            // LevelChunk does this before onPlace when the block type changed.
            if (oldBlock != newBlock) {
                invoke(access.affectNeighborsAfterRemoval, oldState, level, nmsPosition, false);
            }
            invoke(access.onPlace, newState, level, nmsPosition, oldState, false);
            notifyNeighbors(access, level, nmsPosition, newState, oldBlock);

            int shapeFlags = access.updateAll & ~(access.updateSuppressDrops | access.updateNeighbors);
            int recursionLeft = access.updateLimit - 1;
            invoke(access.updateIndirectNeighbourShapes, oldState, level, nmsPosition, shapeFlags, recursionLeft);
            invoke(access.updateNeighbourShapes, newState, level, nmsPosition, shapeFlags, recursionLeft);
            invoke(access.updateIndirectNeighbourShapes, newState, level, nmsPosition, shapeFlags, recursionLeft);
        } catch (ReflectiveOperationException | LinkageError ex) {
            logFallbackOnce(ex);
            PaperWorldAccess.fallbackReplayPlacement(world, position);
        }
    }

    /** Re-notifies neighbors during delayed convergence without replaying onPlace a second time. */
    static void refreshNeighbors(World world, BlockPos position) {
        try {
            NmsAccess access = access(world);
            Object level = invoke(access.getHandle, world);
            Object nmsPosition = access.blockPosConstructor.newInstance(position.x(), position.y(), position.z());
            Block block = world.getBlockAt(position.x(), position.y(), position.z());
            Object state = invoke(access.blockDataGetState, block.getBlockData());
            notifyNeighbors(access, level, nmsPosition, state, invoke(access.stateGetBlock, state));

            int shapeFlags = access.updateAll & ~(access.updateSuppressDrops | access.updateNeighbors);
            int recursionLeft = access.updateLimit - 1;
            invoke(access.updateIndirectNeighbourShapes, state, level, nmsPosition, shapeFlags, recursionLeft);
            invoke(access.updateNeighbourShapes, state, level, nmsPosition, shapeFlags, recursionLeft);
            invoke(access.updateIndirectNeighbourShapes, state, level, nmsPosition, shapeFlags, recursionLeft);
        } catch (ReflectiveOperationException | LinkageError ex) {
            logFallbackOnce(ex);
            PaperWorldAccess.fallbackReplayPlacement(world, position);
        }
    }

    private static void notifyNeighbors(NmsAccess access, Object level, Object position,
                                        Object newState, Object sourceBlock)
            throws ReflectiveOperationException {
        invoke(access.updateNeighborsAt, level, position, sourceBlock);
        if ((boolean) invoke(access.hasAnalogOutputSignal, newState)) {
            invoke(access.updateNeighbourForOutputSignal, level, position,
                    invoke(access.stateGetBlock, newState));
        }
    }

    private static NmsAccess access(World world) throws ReflectiveOperationException {
        NmsAccess result = cachedAccess;
        if (result != null) return result;
        ReflectiveOperationException failure = accessFailure;
        if (failure != null) throw failure;

        synchronized (PaperBlockPhysicsHelper.class) {
            if (cachedAccess != null) return cachedAccess;
            if (accessFailure != null) throw accessFailure;
            try {
                cachedAccess = new NmsAccess(world.getClass().getClassLoader());
                return cachedAccess;
            } catch (ReflectiveOperationException ex) {
                accessFailure = ex;
                throw ex;
            }
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Paper block physics callback failed", cause);
        }
    }

    private static void logFallbackOnce(Throwable ex) {
        if (FALLBACK_LOGGED.compareAndSet(false, true)) {
            Bukkit.getLogger().log(Level.SEVERE,
                    "Could not access Paper's block physics callbacks; using state-replacement fallback", ex);
        }
    }

    private static final class NmsAccess {
        private final Constructor<?> blockPosConstructor;
        private final Method getHandle;
        private final Method blockDataGetState;
        private final Method stateGetBlock;
        private final Method affectNeighborsAfterRemoval;
        private final Method onPlace;
        private final Method updateNeighborsAt;
        private final Method hasAnalogOutputSignal;
        private final Method updateNeighbourForOutputSignal;
        private final Method updateNeighbourShapes;
        private final Method updateIndirectNeighbourShapes;
        private final int updateAll;
        private final int updateNeighbors;
        private final int updateSuppressDrops;
        private final int updateLimit;

        private NmsAccess(ClassLoader classLoader) throws ReflectiveOperationException {
            Class<?> blockPos = load(classLoader, "net.minecraft.core.BlockPos");
            Class<?> block = load(classLoader, "net.minecraft.world.level.block.Block");
            Class<?> state = load(classLoader, "net.minecraft.world.level.block.state.BlockState");
            Class<?> level = load(classLoader, "net.minecraft.world.level.Level");
            Class<?> levelAccessor = load(classLoader, "net.minecraft.world.level.LevelAccessor");
            Class<?> serverLevel = load(classLoader, "net.minecraft.server.level.ServerLevel");
            Class<?> craftBlockData = load(classLoader, "org.bukkit.craftbukkit.block.data.CraftBlockData");
            Class<?> craftWorld = load(classLoader, "org.bukkit.craftbukkit.CraftWorld");

            this.blockPosConstructor = blockPos.getConstructor(int.class, int.class, int.class);
            this.getHandle = craftWorld.getMethod("getHandle");
            this.blockDataGetState = craftBlockData.getMethod("getState");
            this.stateGetBlock = state.getMethod("getBlock");
            this.affectNeighborsAfterRemoval = state.getMethod(
                    "affectNeighborsAfterRemoval", serverLevel, blockPos, boolean.class);
            this.onPlace = state.getMethod("onPlace", level, blockPos, state, boolean.class);
            this.updateNeighborsAt = level.getMethod("updateNeighborsAt", blockPos, block);
            this.hasAnalogOutputSignal = state.getMethod("hasAnalogOutputSignal");
            this.updateNeighbourForOutputSignal = level.getMethod("updateNeighbourForOutputSignal", blockPos, block);
            this.updateNeighbourShapes = state.getMethod(
                    "updateNeighbourShapes", levelAccessor, blockPos, int.class, int.class);
            this.updateIndirectNeighbourShapes = state.getMethod(
                    "updateIndirectNeighbourShapes", levelAccessor, blockPos, int.class, int.class);
            this.updateAll = staticInt(block, "UPDATE_ALL");
            this.updateNeighbors = staticInt(block, "UPDATE_NEIGHBORS");
            this.updateSuppressDrops = staticInt(block, "UPDATE_SUPPRESS_DROPS");
            this.updateLimit = staticInt(block, "UPDATE_LIMIT");
        }

        private static Class<?> load(ClassLoader classLoader, String name) throws ClassNotFoundException {
            return Class.forName(name, true, classLoader);
        }

        private static int staticInt(Class<?> type, String name) throws ReflectiveOperationException {
            Field field = type.getField(name);
            return field.getInt(null);
        }
    }
}
