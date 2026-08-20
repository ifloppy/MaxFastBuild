package dev.maxfastbuild.fabric.client;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.BuildMode;
import dev.maxfastbuild.core.protocol.CommandChunkAssembler;
import dev.maxfastbuild.core.protocol.CompactBreakCommand;
import dev.maxfastbuild.core.protocol.CompactPlaceCommand;
import dev.maxfastbuild.fabric.client.platform.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

final class ClientSession {
    private ClientSession() {}

    /** Compact single-command place intent. Server regenerates the shape. */
    static void sendPlace(String mode, int x1, int y1, int z1, int x2, int y2, int z2, int hollow, String material) {
        sendPlace(mode, x1, y1, z1, x2, y2, z2, hollow, material, null, 1, 1, 1);
    }

    static void sendPlace(String mode, int x1, int y1, int z1, int x2, int y2, int z2, int hollow, String material,
                          BlockPos third, int spacingX, int spacingY, int spacingZ) {
        CompactPlaceCommand.Intent intent = new CompactPlaceCommand.Intent(
                BuildMode.valueOf(mode.toUpperCase(Locale.ROOT)),
                new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), hollow, material,
                third, spacingX, spacingY, spacingZ);
        send(CompactPlaceCommand.format(intent));
    }

    /** Compact single-command break intent. Server regenerates the shape. */
    static void sendBreak(String mode, int x1, int y1, int z1, int x2, int y2, int z2, int hollow) {
        sendBreak(mode, x1, y1, z1, x2, y2, z2, hollow, null, 1, 1, 1);
    }

    static void sendBreak(String mode, int x1, int y1, int z1, int x2, int y2, int z2, int hollow,
                          BlockPos third, int spacingX, int spacingY, int spacingZ) {
        CompactBreakCommand.Intent intent = new CompactBreakCommand.Intent(
                BuildMode.valueOf(mode.toUpperCase(Locale.ROOT)),
                new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), hollow,
                third, spacingX, spacingY, spacingZ);
        send(CompactBreakCommand.format(intent));
    }

    /** Store the radial selection mode in the server-side /mfb selection. */
    static void sendSelectionMode(String mode) {
        if (mode == null || mode.isBlank()) return;
        send("mfb mode " + mode);
    }

    static void sendSelectionHollow(int hollow) {
        send("mfb hollow " + hollow);
    }

    static void sendSelectionSpacing(int x, int y, int z) {
        send("mfb array-spacing " + x + " " + y + " " + z);
    }

    /** Store a client-picked anchor in the server-side /mfb selection. */
    static void sendSelectionPosition(int index, BlockPos position) {
        if (index < 1 || index > 3 || position == null) return;
        send("mfb pos" + index + " " + position.x() + " " + position.y() + " " + position.z());
    }

    private static void send(String command) {
        if (command.length() > CommandChunkAssembler.MAX_COMMAND_LENGTH) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                ClientPlatform.instance().sendSystemMessage(Component.translatable("maxfastbuild.error.command_too_long"));
            }
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) client.getConnection().sendCommand(command);
    }
}
