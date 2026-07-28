package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.AuditService;
import dev.maxfastbuild.api.BlockMutation;
import dev.maxfastbuild.api.OperationKind;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Logs MaxFastBuild mutations to CoreProtect under the requesting player name.
 * <p>
 * Important: break logging must use the <em>pre-break</em> block data from the mutation.
 * After {@code breakNaturally}, the world block is already air — logging {@code block.getState()}
 * would attribute an air removal and lookup/rollback would be wrong.
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
            // CE builds may report various API versions; require a known logging surface.
            if (version < 9) {
                LOG.warning("CoreProtect API version " + version + " is too old (need >= 9)");
                return new CoreProtectAuditService(null);
            }
            LOG.info("CoreProtect audit enabled (API " + version + ")");
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
        if (api == null || playerName == null || playerName.isBlank()) return;
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) return;
        Location location = new Location(
                bukkitWorld,
                mutation.position().x(),
                mutation.position().y(),
                mutation.position().z());
        try {
            boolean ok;
            if (kind == OperationKind.BREAK) {
                // Log the block that was removed (expectedState), not post-break air.
                BlockData removed = Bukkit.createBlockData(mutation.expectedState());
                ok = api.logRemoval(playerName, location, removed.getMaterial(), removed);
            } else {
                // Log the block that was placed (targetState).
                BlockData placed = Bukkit.createBlockData(mutation.targetState());
                ok = api.logPlacement(playerName, location, placed.getMaterial(), placed);
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
