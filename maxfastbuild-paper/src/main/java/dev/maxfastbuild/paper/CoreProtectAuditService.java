package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.AuditService;
import dev.maxfastbuild.api.BlockMutation;
import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.OperationKind;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * CoreProtect logging aligned with the actual notification path.
 * <ul>
 *   <li>A standard {@code BlockPlaceEvent} is already consumed by CoreProtect, so an emitted
 *       placement event suppresses the API {@code logPlacement} fallback.</li>
 *   <li>A successful player {@code BlockBreakEvent} path is already consumed by CoreProtect, so
 *       the API {@code logRemoval} fallback is suppressed when MFB reports that event as emitted.</li>
 * </ul>
 */
final class CoreProtectAuditService implements AuditService {
    private static final Logger LOG = Logger.getLogger("MaxFastBuild");
    private final CoreProtectAPI api;

    CoreProtectAuditService(CoreProtectAPI api) {
        this.api = api;
    }

    static CoreProtectAuditService discover() {
        var plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (!(plugin instanceof CoreProtect coreProtect)) {
            return new CoreProtectAuditService(null);
        }
        try {
            CoreProtectAPI discovered = coreProtect.getAPI();
            if (discovered == null || !discovered.isEnabled()) {
                LOG.warning("CoreProtect is present but API is disabled");
                return new CoreProtectAuditService(null);
            }
            int version = discovered.APIVersion();
            if (version < 9) {
                LOG.warning("CoreProtect API version " + version + " is too old (need >= 9)");
                return new CoreProtectAuditService(null);
            }
            LOG.info("CoreProtect audit enabled (API " + version
                    + ", event-aware dedup: placement event preferred, API fallback otherwise)");
            return new CoreProtectAuditService(discovered);
        } catch (LinkageError | RuntimeException ex) {
            LOG.warning("CoreProtect API not usable: " + ex.getMessage());
            return new CoreProtectAuditService(null);
        }
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
        record(playerId, playerName, world, mutation, kind, breakAlreadyLogged, false);
    }

    @Override
    public void record(UUID playerId, String playerName, String world, BlockMutation mutation,
                       OperationKind kind, boolean breakAlreadyLogged, boolean placeEventAlreadyLogged) {
        if (api == null || playerName == null || playerName.isBlank()) return;
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) return;
        Location location = new Location(
                bukkitWorld,
                mutation.position().x(),
                mutation.position().y(),
                mutation.position().z());
        try {
            boolean ok = true;
            if (kind == OperationKind.BREAK) {
                // Player#breakBlock normally reaches CoreProtect through BlockBreakEvent. Keep the
                // direct API path only as a fallback for mutations that did not emit that event.
                if (!breakAlreadyLogged) {
                    BlockData removed = Bukkit.createBlockData(mutation.expectedState());
                    if (!removed.getMaterial().isAir()) {
                        ok = api.logRemoval(playerName, location, removed.getMaterial(), removed);
                    }
                }
            } else {
                // A replacement can have two audit actions at one coordinate: removal of the old
                // solid block and placement of the new block. Each side independently falls back
                // only when the platform/event path did not already notify CoreProtect.
                BlockData expected = Bukkit.createBlockData(mutation.expectedState());
                Material expectedMat = expected.getMaterial();
                if (!breakAlreadyLogged && !expectedMat.isAir()
                        && !PaperWorldAccess.isReplaceableOccupant(expectedMat)) {
                    ok = api.logRemoval(playerName, location, expectedMat, expected);
                }
                BlockData placed = Bukkit.createBlockData(mutation.targetState());
                if (!placeEventAlreadyLogged && !placed.getMaterial().isAir()) {
                    ok = api.logPlacement(playerName, location, placed.getMaterial(), placed) && ok;
                }
            }
            if (!ok) {
                LOG.fine(() -> "CoreProtect log returned false for " + kind + " by " + playerName + " at " + location);
            }
        } catch (IllegalArgumentException ex) {
            LOG.warning("CoreProtect skip bad block data for " + kind + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            LOG.warning("CoreProtect log failed: " + ex.getMessage());
        }
    }

    @Override
    public void beforeContainerMutation(UUID playerId, String playerName, String world, BlockPos pos) {
        if (api == null || playerName == null || playerName.isBlank() || world == null || pos == null) return;
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) return;
        // Registers a logContainerTransaction that captures the exact inventory write we then perform.
        // Called immediately before we setItem into the container so CoreProtect can diff its inventory.
        try {
            api.logContainerTransaction(playerName, new Location(bukkitWorld, pos.x(), pos.y(), pos.z()));
        } catch (RuntimeException ex) {
            LOG.warning("CoreProtect container transaction log failed: " + ex.getMessage());
        }
    }
}
