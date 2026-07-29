package dev.maxfastbuild.api;

import java.util.UUID;

public interface AuditService {
    boolean available();

    void record(UUID playerId, String playerName, String world, BlockMutation mutation, OperationKind kind);

    /**
     * @param breakAlreadyLogged true when the platform already notified loggers via a break event
     *                           (skip a second CoreProtect {@code logRemoval})
     */
    default void record(UUID playerId, String playerName, String world, BlockMutation mutation,
                        OperationKind kind, boolean breakAlreadyLogged) {
        record(playerId, playerName, world, mutation, kind);
    }
}
