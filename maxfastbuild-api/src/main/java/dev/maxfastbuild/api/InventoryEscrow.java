package dev.maxfastbuild.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InventoryEscrow {
    Reservation reserve(UUID playerId, Map<String, Long> items, boolean searchShulkerBoxes);
    void consume(String reservationId, String item, long amount);
    void release(String reservationId, UUID playerId);

    record Reservation(boolean successful, String id, Map<String, Long> reserved, List<String> errors) {}
}
