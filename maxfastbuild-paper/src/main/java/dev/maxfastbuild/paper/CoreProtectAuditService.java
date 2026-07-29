package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.AuditService;
import dev.maxfastbuild.api.BlockMutation;
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
 * CoreProtect logging aligned with vanilla mutation path:
 * <ul>
 *   <li><b>Break</b> ({@code breakNaturally}): {@code breakNaturally} may not fire
 *       {@code BlockBreakEvent} on all Paper/Leaf versions, so we call
 *       {@code logRemoval} ourselves once (exactly one record).</li>
 *   <li><b>Place</b> ({@code setBlockData}): CP is not auto-notified — {@code logPlacement} once.
 *       For solid occupants the removal is also logged here since {@code breakNaturally}
 *       may not fire the event. Air/replaceable occupants skip removal logging.</li>
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
                    + ", break=audit logRemoval, place=audit logPlacement + logRemoval for solid)");
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
                // breakNaturally may not fire BlockBreakEvent on all Paper/Leaf versions.
                // Call logRemoval ourselves once to guarantee exactly one record.
                BlockData removed = Bukkit.createBlockData(mutation.expectedState());
                if (!removed.getMaterial().isAir()) {
                    ok = api.logRemoval(playerName, location, removed.getMaterial(), removed);
                }
            } else {
                // Place: log removal of the occupant (if solid non-replaceable) since
                // breakNaturally may not fire BlockBreakEvent on all Paper/Leaf versions.
                BlockData expected = Bukkit.createBlockData(mutation.expectedState());
                Material expectedMat = expected.getMaterial();
                if (!expectedMat.isAir() && !PaperWorldAccess.isReplaceableOccupant(expectedMat)) {
                    ok = api.logRemoval(playerName, location, expectedMat, expected);
                }
                BlockData placed = Bukkit.createBlockData(mutation.targetState());
                if (!placed.getMaterial().isAir()) {
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
}
