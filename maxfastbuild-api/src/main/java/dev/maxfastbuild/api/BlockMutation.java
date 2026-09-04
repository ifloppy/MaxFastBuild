package dev.maxfastbuild.api;

import java.util.Objects;

public record BlockMutation(BlockPos position, String expectedState, String targetState, String targetNbt,
                            boolean preserveContents) {
    public BlockMutation(BlockPos position, String expectedState, String targetState) {
        this(position, expectedState, targetState, null, false);
    }

    public BlockMutation(BlockPos position, String expectedState, String targetState, String targetNbt) {
        this(position, expectedState, targetState, targetNbt, false);
    }

    public BlockMutation {
        Objects.requireNonNull(position);
        Objects.requireNonNull(expectedState);
        Objects.requireNonNull(targetState);
    }
}
