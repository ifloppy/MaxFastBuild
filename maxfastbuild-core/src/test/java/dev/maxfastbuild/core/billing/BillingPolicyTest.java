package dev.maxfastbuild.core.billing;

import dev.maxfastbuild.api.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BillingPolicyTest {
    @Test void combinesAllEnabledPricesAndRefundsVariablePart() {
        Bounds bounds = new Bounds(new BlockPos(0, 0, 0), new BlockPos(2, 3, 4));
        List<BlockMutation> changes = new ArrayList<>();
        for (int i = 0; i < 10; i++) changes.add(new BlockMutation(new BlockPos(i, 0, 0), "minecraft:air", "minecraft:stone"));
        BuildPlan plan = new BuildPlan("world", OperationKind.PLACE, bounds, changes);
        BillingPolicy policy = new BillingPolicy(true, new BigDecimal("5"), true, new BigDecimal("0.10"), true, new BigDecimal("0.50"), 2);

        BillingPolicy.Charge charge = policy.quote(plan);

        assertThat(charge.total()).isEqualByComparingTo("12.00");
        assertThat(policy.refund(charge, 10, 5)).isEqualByComparingTo("3.50");
    }
}
