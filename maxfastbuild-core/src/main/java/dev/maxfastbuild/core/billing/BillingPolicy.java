package dev.maxfastbuild.core.billing;

import dev.maxfastbuild.api.BuildPlan;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record BillingPolicy(boolean perOperationEnabled, BigDecimal perOperation,
                            boolean perAreaEnabled, BigDecimal perArea,
                            boolean perBlockEnabled, BigDecimal perBlock,
                            int fractionalDigits) {
    public BillingPolicy {
        if (fractionalDigits < 0) throw new IllegalArgumentException("fractionalDigits cannot be negative");
        requireNonNegative(perOperation);
        requireNonNegative(perArea);
        requireNonNegative(perBlock);
    }

    public Charge quote(BuildPlan plan) {
        BigDecimal operation = perOperationEnabled ? perOperation : BigDecimal.ZERO;
        BigDecimal area = perAreaEnabled ? perArea.multiply(BigDecimal.valueOf(plan.bounds().maximumPlaneArea())) : BigDecimal.ZERO;
        BigDecimal blocks = perBlockEnabled ? perBlock.multiply(BigDecimal.valueOf(plan.blockCount())) : BigDecimal.ZERO;
        return new Charge(operation, area, blocks, operation.add(area).add(blocks).setScale(fractionalDigits, RoundingMode.HALF_UP));
    }

    /**
     * Refund variable (area + block) fees for work not completed.
     * {@code charge.operation()} is not included here — caller decides operation-fee policy.
     */
    public BigDecimal refund(Charge charge, long planned, long completed) {
        if (planned <= 0 || completed >= planned) return BigDecimal.ZERO.setScale(fractionalDigits);
        BigDecimal ratio = BigDecimal.valueOf(planned - completed).divide(BigDecimal.valueOf(planned), 12, RoundingMode.HALF_UP);
        return charge.area().add(charge.blocks()).multiply(ratio).setScale(fractionalDigits, RoundingMode.HALF_UP);
    }

    private static void requireNonNegative(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) throw new IllegalArgumentException("Prices must be non-negative");
    }

    public record Charge(BigDecimal operation, BigDecimal area, BigDecimal blocks, BigDecimal total) {}
}
