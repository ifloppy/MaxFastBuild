package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.AuditService;
import dev.maxfastbuild.api.BlockMutation;
import dev.maxfastbuild.api.OperationKind;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.prism_mc.prism.api.actions.Action;
import org.prism_mc.prism.api.actions.types.ActionType;
import org.prism_mc.prism.api.activities.Activity;
import org.prism_mc.prism.api.activities.Cause;
import org.prism_mc.prism.api.containers.PlayerContainer;
import org.prism_mc.prism.paper.api.PrismPaperApi;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Logs MaxFastBuild mutations to Prism so they appear as the player's real actions (block place,
 * block break for replaced occupants, and item-insert for block-entity contents like hoppers).
 * Prism registers {@link PrismPaperApi} via Bukkit's ServicesManager; without it this is a no-op.
 */
final class PrismAuditService implements AuditService {
    private static final Logger LOG = Logger.getLogger("MaxFastBuild");
    private final PrismPaperApi api;

    PrismAuditService(PrismPaperApi api) {
        this.api = api;
    }

    static PrismAuditService discover() {
        try {
            RegisteredServiceProvider<PrismPaperApi> provider =
                    Bukkit.getServicesManager().getRegistration(PrismPaperApi.class);
            if (provider != null && provider.getProvider() != null) {
                LOG.info("Prism audit enabled");
                return new PrismAuditService(provider.getProvider());
            }
        } catch (LinkageError | RuntimeException ex) {
            LOG.warning("Prism API not usable: " + ex.getMessage());
        }
        return new PrismAuditService(null);
    }

    @Override
    public boolean available() {
        return api != null;
    }

    @Override
    public void record(UUID playerId, String playerName, String world, BlockMutation mutation, OperationKind kind) {
        record(playerId, playerName, world, mutation, kind, false);
    }

    @Override
    public void record(UUID playerId, String playerName, String world, BlockMutation mutation,
                       OperationKind kind, boolean breakAlreadyLogged) {
        if (api == null || playerName == null || playerName.isBlank()) return;
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) return;
        try {
            if (kind == OperationKind.BREAK) {
                logBlock(bukkitWorld, mutation.position(), "block-break", mutation.expectedState(), playerName, playerId);
                return;
            }
            BlockData expected = safeBlockData(mutation.expectedState());
            boolean replacedSolid = expected != null && !expected.getMaterial().isAir()
                    && !PaperWorldAccess.isReplaceableOccupant(expected.getMaterial());
            if (replacedSolid && !breakAlreadyLogged) {
                logBlock(bukkitWorld, mutation.position(), "block-break", mutation.expectedState(), playerName, playerId);
            }
            logBlock(bukkitWorld, mutation.position(), "block-place", mutation.targetState(), playerName, playerId);
            logContainerItems(bukkitWorld, mutation.position(), mutation.targetState(), mutation.targetNbt(),
                    playerName, playerId);
        } catch (RuntimeException ex) {
            LOG.warning("Prism log failed: " + ex.getMessage());
        }
    }

    private void logBlock(World world, dev.maxfastbuild.api.BlockPos pos, String actionKey, String state,
                          String playerName, UUID playerId) {
        BlockData data = safeBlockData(state);
        if (data == null || data.getMaterial().isAir()) return;
        BlockState snapshot = world.getBlockAt(pos.x(), pos.y(), pos.z()).getState();
        snapshot.setBlockData(data);
        recordActivity(world, pos, api.actionFactory().createBlockAction(actionType(actionKey), snapshot),
                playerName, playerId);
    }

    private void logContainerItems(World world, dev.maxfastbuild.api.BlockPos pos, String targetState, String targetNbt,
                                   String playerName, UUID playerId) {
        if (targetNbt == null) return;
        BlockData data = safeBlockData(targetState);
        if (data == null) return;
        Material material = data.getMaterial();
        PaperNbtHelper.NbtCheck check =
                PaperNbtHelper.validateForBlock(targetNbt, material, PaperNbtHelper.registryAccess(world));
        if (!(check instanceof PaperNbtHelper.NbtCheck.Ok ok)) return;
        for (PaperNbtHelper.ItemInstance item : ok.items()) {
            if (item.bukkit() == null || item.bukkit().getType().isAir() || item.count() <= 0) continue;
            ItemStack stack = item.bukkit().clone();
            stack.setAmount(1);
            recordActivity(world, pos,
                    api.actionFactory().createItemAction(actionType("item-insert"), stack,
                            (int) Math.min(item.count(), Integer.MAX_VALUE), "maxfastbuild-paste"),
                    playerName, playerId);
        }
    }

    private void recordActivity(World world, dev.maxfastbuild.api.BlockPos pos, Action action,
                                String playerName, UUID playerId) {
        if (action == null) return;
        Activity activity = Activity.builder()
                .action(action)
                .world(world.getUID(), world.getName())
                .coordinate(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5)
                .cause(new Cause(new PlayerContainer(playerName, playerId)))
                .build();
        api.recordingService().addToQueue(activity);
    }

    private ActionType actionType(String key) {
        return api.actionTypeRegistry().actionType(key).orElse(null);
    }

    private static BlockData safeBlockData(String state) {
        if (state == null || state.isBlank()) return null;
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
