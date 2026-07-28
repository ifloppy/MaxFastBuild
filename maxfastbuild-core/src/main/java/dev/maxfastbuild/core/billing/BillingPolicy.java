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
        return quote(plan, 0);
    }

    /**
     * @param replaceBreakCount solid blocks that must be broken before place (extra per-block fee each)
     */
    public Charge quote(BuildPlan plan, long replaceBreakCount) {
        if (replaceBreakCount < 0) throw new IllegalArgumentException("replaceBreakCount cannot be negative");
        BigDecimal operation = perOperationEnabled ? perOperation : BigDecimal.ZERO;
        BigDecimal area = perAreaEnabled ? perArea.multiply(BigDecimal.valueOf(plan.bounds().maximumPlaneArea())) : BigDecimal.ZERO;
        long billableBlocks = plan.blockCount() + replaceBreakCount;
        BigDecimal blocks = perBlockEnabled ? perBlock.multiply(BigDecimal.valueOf(billableBlocks)) : BigDecimal.ZERO;
        return new Charge(operation, area, blocks, operation.add(area).add(blocks).setScale(fractionalDigits, RoundingMode.HALF_UP));
    }

    /**
     * Refund variable (area + block) fees for work not completed.
     * {@code charge.operation()} is not included here — caller decides operation-fee policy.
     * When {@code replaceBreakCount} &gt; 0, each unfinished place mutation refunds place+break per-block share.
     */
    public BigDecimal refund(Charge charge, long planned, long completed) {
        return refund(charge, planned, completed, 0);
    }

    public BigDecimal refund(Charge charge, long planned, long completed, long replaceBreakCount) {
        if (planned <= 0 || completed >= planned) return BigDecimal.ZERO.setScale(fractionalDigits);
        // Variable fees were computed for (planned places + replace breaks). Unfinished places
        // proportionally unwind both the place and (if any) the bundled replace-break share.
        long unfinished = planned - completed;
        BigDecimal placeShare = perBlockEnabled
                ? perBlock.multiply(BigDecimal.valueOf(unfinished))
                : BigDecimal.ZERO;
        BigDecimal replaceShare = BigDecimal.ZERO;
        if (perBlockEnabled && replaceBreakCount > 0 && planned > 0) {
            // Attribute replace fees evenly across planned place mutations that needed a break.
            // Conservative: refund replace share only for unfinished work, capped by replaceBreakCount.
            long replaceUnfinished = Math.min(replaceBreakCount, unfinished);
            replaceShare = perBlock.multiply(BigDecimal.valueOf(replaceUnfinished));
        }
        BigDecimal areaPart = charge.area();
        BigDecimal areaRefund = BigDecimal.ZERO;
        if (areaPart.signum() > 0) {
            BigDecimal ratio = BigDecimal.valueOf(unfinished).divide(BigDecimal.valueOf(planned), 12, RoundingMode.HALF_UP);
            areaRefund = areaPart.multiply(ratio);
        }
        return placeShare.add(replaceShare).add(areaRefund).setScale(fractionalDigits, RoundingMode.HALF_UP);
    }

    private static void requireNonNegative(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) throw new IllegalArgumentException("Prices must be non-negative");
    }

    public record Charge(BigDecimal operation, BigDecimal area, BigDecimal blocks, BigDecimal total) {}
}
