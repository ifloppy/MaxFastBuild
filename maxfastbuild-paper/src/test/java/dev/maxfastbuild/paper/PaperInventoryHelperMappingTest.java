package dev.maxfastbuild.paper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaperInventoryHelperMappingTest {
    @Test void mapsBlockOnlyStatesToTheItemsPlayersActuallyUse() {
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:redstone_wire[power=7]"))
                .isEqualTo("minecraft:redstone");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:tripwire[attached=false]"))
                .isEqualTo("minecraft:string");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:wall_torch[facing=north]"))
                .isEqualTo("minecraft:torch");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:redstone_wall_torch[facing=east,lit=true]"))
                .isEqualTo("minecraft:redstone_torch");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:cocoa[age=2,facing=south]"))
                .isEqualTo("minecraft:cocoa_beans");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:wheat[age=7]"))
                .isEqualTo("minecraft:wheat_seeds");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:attached_melon_stem[facing=west]"))
                .isEqualTo("minecraft:melon_seeds");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:cave_vines_plant[berries=true]"))
                .isEqualTo("minecraft:glow_berries");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:bamboo_sapling[stage=0]"))
                .isEqualTo("minecraft:bamboo");
    }

    @Test void ordinaryAndGenericWallVariantsRemainStable() {
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:stone"))
                .isEqualTo("minecraft:stone");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:oak_wall_sign[facing=north]"))
                .isEqualTo("minecraft:oak_sign");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:white_wall_banner[rotation=0]"))
                .isEqualTo("minecraft:white_banner");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:soul_wall_torch[facing=east]"))
                .isEqualTo("minecraft:soul_torch");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:player_wall_head[facing=north]"))
                .isEqualTo("minecraft:player_head");
        assertThat(PaperInventoryHelper.itemKeyFromBlockState("minecraft:wither_skeleton_wall_skull[facing=east]"))
                .isEqualTo("minecraft:wither_skeleton_skull");
    }

    @Test void powderSnowUsesBucketTokenSemantics() {
        // isFreeBlock() also asks Paper's live registry whether the material is air, which is not
        // available in this plain-JUnit test process. The live Leaf self-test covers that branch.
        assertThat(PaperInventoryHelper.isFluid(org.bukkit.Material.POWDER_SNOW)).isTrue();
    }
}
