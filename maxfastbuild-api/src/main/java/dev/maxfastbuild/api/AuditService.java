package dev.maxfastbuild.api;

import java.util.UUID;

public interface AuditService {
    boolean available();
    void record(UUID playerId, String playerName, String world, BlockMutation mutation, OperationKind kind);
}
