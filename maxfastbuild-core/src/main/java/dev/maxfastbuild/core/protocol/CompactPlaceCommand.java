package dev.maxfastbuild.core.protocol;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.BuildMode;
import java.util.Locale;
import java.util.Objects;

/** Compact place intent; arc/array parameters are inserted before the material when needed. */
public final class CompactPlaceCommand {
    private CompactPlaceCommand() {}

    public record Intent(BuildMode mode, BlockPos first, BlockPos second, int hollow, String material,
                         BlockPos third, int spacingX, int spacingY, int spacingZ) {
        public Intent {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
            Objects.requireNonNull(material);
        }

        public Intent(BuildMode mode, BlockPos first, BlockPos second, int hollow, String material) {
            this(mode, first, second, hollow, material, null, 1, 1, 1);
        }

        public Intent(BuildMode mode, BlockPos first, BlockPos second, BlockPos third,
                      int hollow, String material) {
            this(mode, first, second, hollow, material, third, 1, 1, 1);
        }

        public Intent(BuildMode mode, BlockPos first, BlockPos second, BlockPos third,
                      int hollow, String material, int spacingX, int spacingY, int spacingZ) {
            this(mode, first, second, hollow, material, third, spacingX, spacingY, spacingZ);
        }
    }

    public static String format(Intent intent) {
        StringBuilder command = new StringBuilder("__mfb place ")
                .append(intent.mode().name().toLowerCase(Locale.ROOT))
                .append(' ').append(intent.first().x()).append(' ').append(intent.first().y()).append(' ').append(intent.first().z())
                .append(' ').append(intent.second().x()).append(' ').append(intent.second().y()).append(' ').append(intent.second().z())
                .append(' ').append(intent.hollow());
        appendParameters(command, intent);
        return command.append(' ').append(intent.material()).toString();
    }

    /** Accepts command with or without leading slash. */
    public static Intent parse(String raw) {
        String message = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = message.split(" ", -1);
        if (parts.length < 11 || !parts[0].equals("__mfb") || !parts[1].equals("place")) {
            throw new IllegalArgumentException("place_arity");
        }
        BuildMode mode = BuildMode.valueOf(parts[2].toUpperCase(Locale.ROOT));
        BlockPos first = new BlockPos(Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
        BlockPos second = new BlockPos(Integer.parseInt(parts[6]), Integer.parseInt(parts[7]), Integer.parseInt(parts[8]));
        int hollow = Integer.parseInt(parts[9]);
        Parameters parameters = parseParameters(mode, parts, 10);
        String material = String.join(" ", java.util.Arrays.copyOfRange(parts, parameters.nextIndex, parts.length));
        if (material.isBlank()) throw new IllegalArgumentException("place_material");
        return new Intent(mode, first, second, hollow, material,
                parameters.third, parameters.spacingX, parameters.spacingY, parameters.spacingZ);
    }

    private static void appendParameters(StringBuilder command, Intent intent) {
        if (intent.mode() == BuildMode.ARC) {
            BlockPos third = Objects.requireNonNull(intent.third(), "arc requires three points");
            command.append(' ').append(third.x()).append(' ').append(third.y()).append(' ').append(third.z());
        } else if (intent.mode() == BuildMode.ARRAY
                && (intent.spacingX() != 1 || intent.spacingY() != 1 || intent.spacingZ() != 1)) {
            command.append(' ').append(intent.spacingX()).append(' ').append(intent.spacingY()).append(' ').append(intent.spacingZ());
        }
    }

    private static Parameters parseParameters(BuildMode mode, String[] parts, int index) {
        if (mode == BuildMode.ARC) {
            if (parts.length < index + 4) throw new IllegalArgumentException("arc_arity");
            return new Parameters(new BlockPos(Integer.parseInt(parts[index]), Integer.parseInt(parts[index + 1]),
                    Integer.parseInt(parts[index + 2])), 1, 1, 1, index + 3);
        }
        if (mode == BuildMode.ARRAY && parts.length >= index + 4) {
            return new Parameters(null, Integer.parseInt(parts[index]), Integer.parseInt(parts[index + 1]),
                    Integer.parseInt(parts[index + 2]), index + 3);
        }
        return new Parameters(null, 1, 1, 1, index);
    }

    private record Parameters(BlockPos third, int spacingX, int spacingY, int spacingZ, int nextIndex) {}
}
