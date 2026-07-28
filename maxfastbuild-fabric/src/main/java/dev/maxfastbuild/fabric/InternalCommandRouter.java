package dev.maxfastbuild.fabric;

import net.minecraft.server.level.ServerPlayer;

public final class InternalCommandRouter {
    private InternalCommandRouter() {}
    public static boolean handle(ServerPlayer player, String command) {
        if (!command.equals("__mfb") && !command.startsWith("__mfb ")) return false;
        // TODO: Connect the Fabric platform services to the shared secure request pipeline.
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u2063MFB1:{\"mfb\":1,\"type\":\"error\",\"messageKey\":\"maxfastbuild.error.fabric_server_todo\"}"));
        return true;
    }
}
