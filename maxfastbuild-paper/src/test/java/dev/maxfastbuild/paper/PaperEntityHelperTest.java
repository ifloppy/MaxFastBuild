package dev.maxfastbuild.paper;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;

class PaperEntityHelperTest {
    @Test
    void hopperMinecartIsARecognizedBillablePasteEntity() {
        assertThat(PaperEntityHelper.billableItem("minecraft:hopper_minecart"))
                .isEqualTo(Material.HOPPER_MINECART);
    }

    @Test
    void exposesTheUnderlyingNmsSpawnError() {
        InvocationTargetException reflected = new InvocationTargetException(
                new IllegalArgumentException("bad entity data"));

        assertThat(PaperEntityHelper.failureReason(reflected))
                .isEqualTo("IllegalArgumentException: bad entity data");
    }
}
