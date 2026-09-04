package dev.maxfastbuild.paper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PluginMessagesTest {
    private static final Map<String, Object> ACCEPTED_DATA = Map.of(
            "blocks", 374,
            "regionBlocks", 4800,
            "sizeX", 20,
            "sizeY", 12,
            "sizeZ", 20,
            "charge", "113.20");

    @Test
    void legacyAcceptedMessageUsesChargeAsSecondArgument() {
        assertThat(PluginMessages.acceptedArguments(
                "<green>任务已接受：{0} 个方块，费用 {1}。</green>", ACCEPTED_DATA))
                .containsExactly(374, "113.20");
    }

    @Test
    void detailedAcceptedMessageKeepsRegionFieldsAndCharge() {
        assertThat(PluginMessages.acceptedArguments(
                "<green>任务已接受：影响 {0} 格，选区 {1} 格（{2}×{3}×{4}），费用 {5}。</green>",
                ACCEPTED_DATA))
                .containsExactly(374, 4800, 20, 12, 20, "113.20");
    }
}
