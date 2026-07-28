package dev.maxfastbuild.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class MaxFastBuildFabric implements ModInitializer {
    public static final String MOD_ID = "maxfastbuild";

    @Override public void onInitialize() {
        // Client-side radial UI is the primary UX. These commands are informational stubs on Fabric hosts.
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("mfb")
                        .executes(command -> {
                            command.getSource().sendSuccess(
                                    () -> Component.literal("Use the MaxFastBuild radial menu on a Paper/Leaf server. Local stubs: /mfb mode <mode> | status"),
                                    false);
                            return 1;
                        })
                        .then(Commands.literal("mode").then(Commands.argument("mode", StringArgumentType.word()).executes(command -> {
                            command.getSource().sendSuccess(() -> Component.literal("Mode noted locally: " + StringArgumentType.getString(command, "mode") + " (server builds require Paper plugin)"), false);
                            return 1;
                        })))
                        .then(Commands.literal("status").executes(command -> {
                            command.getSource().sendSuccess(() -> Component.literal("No local Fabric server build pipeline"), false);
                            return 1;
                        }))
        ));
    }
}
