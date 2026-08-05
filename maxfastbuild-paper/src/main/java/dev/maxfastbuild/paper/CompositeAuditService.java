package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.AuditService;
import dev.maxfastbuild.api.BlockMutation;
import dev.maxfastbuild.api.OperationKind;

import java.util.List;
import java.util.UUID;

/**
 * Dispatches audit records to every available logging backend (CoreProtect, Prism, …).
 * Available when at least one backend is present.
 */
final class CompositeAuditService implements AuditService {
    private final List<AuditService> services;

    CompositeAuditService(List<AuditService> services) {
        this.services = List.copyOf(services);
    }

    @Override
    public boolean available() {
        for (AuditService service : services) {
            if (service.available()) return true;
        }
        return false;
    }

    @Override
    public void record(UUID playerId, String playerName, String world, BlockMutation mutation, OperationKind kind) {
        for (AuditService service : services) {
            if (service.available()) service.record(playerId, playerName, world, mutation, kind);
        }
    }

    @Override
    public void record(UUID playerId, String playerName, String world, BlockMutation mutation,
                       OperationKind kind, boolean breakAlreadyLogged) {
        for (AuditService service : services) {
            if (service.available()) service.record(playerId, playerName, world, mutation, kind, breakAlreadyLogged);
        }
    }
}
