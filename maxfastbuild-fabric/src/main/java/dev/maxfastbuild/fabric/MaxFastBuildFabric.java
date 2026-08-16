package dev.maxfastbuild.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.maxfastbuild.api.BuildMode;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public final class MaxFastBuildFabric implements ModInitializer {
    public static final String MOD_ID = "maxfastbuild";

    @Override public void onInitialize() {
        // Client-side radial UI is the primary UX. These commands are informational stubs on Fabric hosts.
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("mfb")
                        .executes(command -> {
                            command.getSource().sendSuccess(
                                    () -> Component.literal("Use the MaxFastBuild radial menu on a Paper/Leaf server. Local commands: /mfb mode | status"),
                                    false);
                            return 1;
                        })
                        .then(Commands.literal("mode")
                                .executes(command -> {
                                    command.getSource().sendSuccess(
                                            () -> Component.literal("Available modes: " + modeList()), false);
                                    return 1;
                                })
                                .then(Commands.argument("mode", StringArgumentType.word()).executes(command -> {
                                    String raw = StringArgumentType.getString(command, "mode");
                                    try {
                                        BuildMode mode = BuildMode.valueOf(raw.toUpperCase(Locale.ROOT));
                                        command.getSource().sendSuccess(() -> Component.literal(
                                                "Mode noted locally: " + mode.name().toLowerCase(Locale.ROOT)
                                                        + " (server builds require Paper plugin)"), false);
                                    } catch (IllegalArgumentException ex) {
                                        command.getSource().sendSuccess(
                                                () -> Component.literal("Unknown mode. Available: " + modeList()), false);
                                    }
                                    return 1;
                                })))
                        .then(Commands.literal("status").executes(command -> {
                            command.getSource().sendSuccess(() -> Component.literal("No local Fabric server build pipeline"), false);
                            return 1;
                        }))
        ));
    }

    private static String modeList() {
        return Arrays.stream(BuildMode.values())
                .map(mode -> mode.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }
}
