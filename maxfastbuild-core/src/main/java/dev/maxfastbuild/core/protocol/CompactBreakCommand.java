package dev.maxfastbuild.core.protocol;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.BuildMode;
import java.util.Locale;
import java.util.Objects;

/** Single-line break intent: __mfb break mode x1 y1 z1 x2 y2 z2 hollow */
public final class CompactBreakCommand {
    private CompactBreakCommand() {}

    public record Intent(BuildMode mode, BlockPos first, BlockPos second, boolean hollow) {
        public Intent {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
        }
    }

    public static String format(Intent intent) {
        return "__mfb break " + intent.mode().name().toLowerCase(Locale.ROOT)
                + " " + intent.first().x() + " " + intent.first().y() + " " + intent.first().z()
                + " " + intent.second().x() + " " + intent.second().y() + " " + intent.second().z()
                + " " + (intent.hollow() ? "1" : "0");
    }

    /** Accepts command with or without leading slash. */
    public static Intent parse(String raw) {
        String message = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = message.split(" ", -1);
        if (parts.length != 10 || !parts[0].equals("__mfb") || !parts[1].equals("break")) {
            throw new IllegalArgumentException("break_arity");
        }
        BuildMode mode = BuildMode.valueOf(parts[2].toUpperCase(Locale.ROOT));
        BlockPos first = new BlockPos(Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
        BlockPos second = new BlockPos(Integer.parseInt(parts[6]), Integer.parseInt(parts[7]), Integer.parseInt(parts[8]));
        boolean hollow = parts[9].equals("1") || Boolean.parseBoolean(parts[9]);
        return new Intent(mode, first, second, hollow);
    }
}
