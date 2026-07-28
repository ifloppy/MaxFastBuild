package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.EconomyService;
import net.milkbowl.vault.economy.*;
import org.bukkit.Bukkit;
import java.math.BigDecimal;
import java.util.UUID;

final class VaultEconomyService implements EconomyService {
    private final Economy economy;
    VaultEconomyService(Economy economy) { this.economy = economy; }

    @Override public TransactionResult withdraw(UUID playerId, BigDecimal amount, String transactionId) {
        EconomyResponse response = economy.withdrawPlayer(Bukkit.getOfflinePlayer(playerId), amount.doubleValue());
        return new TransactionResult(response.transactionSuccess(), response.errorMessage);
    }

    @Override public TransactionResult deposit(UUID playerId, BigDecimal amount, String transactionId) {
        EconomyResponse response = economy.depositPlayer(Bukkit.getOfflinePlayer(playerId), amount.doubleValue());
        return new TransactionResult(response.transactionSuccess(), response.errorMessage);
    }

    @Override public boolean enabled() { return true; }
}
