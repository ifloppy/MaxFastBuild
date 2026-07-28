package dev.maxfastbuild.api;

import java.math.BigDecimal;
import java.util.UUID;

public interface EconomyService {
    TransactionResult withdraw(UUID playerId, BigDecimal amount, String transactionId);
    TransactionResult deposit(UUID playerId, BigDecimal amount, String transactionId);
    boolean enabled();

    record TransactionResult(boolean successful, String message) {}
}
