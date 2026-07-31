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
    static void sendPlace(String mode, int x1, int y1, int z1, int x2, int y2, int z2, boolean hollow, String material) {
        CompactPlaceCommand.Intent intent = new CompactPlaceCommand.Intent(
                BuildMode.valueOf(mode.toUpperCase(Locale.ROOT)),
                new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), hollow, material);
        send(CompactPlaceCommand.format(intent));
    }

    /** Compact single-command break intent. Server regenerates the shape. */
    static void sendBreak(String mode, int x1, int y1, int z1, int x2, int y2, int z2, boolean hollow) {
        CompactBreakCommand.Intent intent = new CompactBreakCommand.Intent(
                BuildMode.valueOf(mode.toUpperCase(Locale.ROOT)),
                new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), hollow);
        send(CompactBreakCommand.format(intent));
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
