package dev.maxfastbuild.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class MaxFastBuildFabric implements ModInitializer {
    public static final String MOD_ID = "maxfastbuild";

    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("mfb")
                        .executes(command -> { command.getSource().sendSuccess(() -> Component.literal("/mfb mode <mode> | status | cancel | undo | redo"), false); return 1; })
                        .then(Commands.literal("mode").then(Commands.argument("mode", StringArgumentType.word()).executes(command -> {
                            command.getSource().sendSuccess(() -> Component.literal("Mode set to " + StringArgumentType.getString(command, "mode")), false);
                            return 1;
                        })))
                        .then(Commands.literal("status").executes(command -> { command.getSource().sendSuccess(() -> Component.literal("No active task"), false); return 1; }))
        ));
    }
}
