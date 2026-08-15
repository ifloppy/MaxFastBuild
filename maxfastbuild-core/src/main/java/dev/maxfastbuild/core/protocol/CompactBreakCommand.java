package dev.maxfastbuild.core.protocol;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.BuildMode;
import java.util.Locale;
import java.util.Objects;

/** Compact break intent; arc/array parameters are appended after the hollow value when needed. */
public final class CompactBreakCommand {
    private CompactBreakCommand() {}

    public record Intent(BuildMode mode, BlockPos first, BlockPos second, int hollow,
                         BlockPos third, int spacingX, int spacingY, int spacingZ) {
        public Intent {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
        }

        public Intent(BuildMode mode, BlockPos first, BlockPos second, int hollow) {
            this(mode, first, second, hollow, null, 1, 1, 1);
        }

        public Intent(BuildMode mode, BlockPos first, BlockPos second, BlockPos third, int hollow) {
            this(mode, first, second, hollow, third, 1, 1, 1);
        }

        public Intent(BuildMode mode, BlockPos first, BlockPos second, BlockPos third,
                      int hollow, int spacingX, int spacingY, int spacingZ) {
            this(mode, first, second, hollow, third, spacingX, spacingY, spacingZ);
        }
    }

    public static String format(Intent intent) {
        StringBuilder command = new StringBuilder("__mfb break ")
                .append(intent.mode().name().toLowerCase(Locale.ROOT))
                .append(' ').append(intent.first().x()).append(' ').append(intent.first().y()).append(' ').append(intent.first().z())
                .append(' ').append(intent.second().x()).append(' ').append(intent.second().y()).append(' ').append(intent.second().z())
                .append(' ').append(intent.hollow());
        if (intent.mode() == BuildMode.ARC) {
            BlockPos third = Objects.requireNonNull(intent.third(), "arc requires three points");
            command.append(' ').append(third.x()).append(' ').append(third.y()).append(' ').append(third.z());
        } else if (intent.mode() == BuildMode.ARRAY
                && (intent.spacingX() != 1 || intent.spacingY() != 1 || intent.spacingZ() != 1)) {
            command.append(' ').append(intent.spacingX()).append(' ').append(intent.spacingY()).append(' ').append(intent.spacingZ());
        }
        return command.toString();
    }

    /** Accepts command with or without leading slash. */
    public static Intent parse(String raw) {
        String message = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = message.split(" ", -1);
        if (parts.length < 10 || !parts[0].equals("__mfb") || !parts[1].equals("break")) {
            throw new IllegalArgumentException("break_arity");
        }
        BuildMode mode = BuildMode.valueOf(parts[2].toUpperCase(Locale.ROOT));
        BlockPos first = new BlockPos(Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
        BlockPos second = new BlockPos(Integer.parseInt(parts[6]), Integer.parseInt(parts[7]), Integer.parseInt(parts[8]));
        int hollow = Integer.parseInt(parts[9]);
        if (mode == BuildMode.ARC) {
            if (parts.length != 13) throw new IllegalArgumentException("arc_arity");
            return new Intent(mode, first, second,
                    new BlockPos(Integer.parseInt(parts[10]), Integer.parseInt(parts[11]), Integer.parseInt(parts[12])),
                    hollow, 1, 1, 1);
        }
        if (mode == BuildMode.ARRAY) {
            if (parts.length == 10) return new Intent(mode, first, second, hollow, null, 1, 1, 1);
            if (parts.length != 13) throw new IllegalArgumentException("array_arity");
            return new Intent(mode, first, second, hollow, null,
                    Integer.parseInt(parts[10]), Integer.parseInt(parts[11]), Integer.parseInt(parts[12]));
        }
        if (parts.length != 10) throw new IllegalArgumentException("break_arity");
        return new Intent(mode, first, second, hollow, null, 1, 1, 1);
    }
}
