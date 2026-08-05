package dev.maxfastbuild.api;

import java.util.Objects;

public record BlockMutation(BlockPos position, String expectedState, String targetState, String targetNbt) {
    public BlockMutation(BlockPos position, String expectedState, String targetState) {
        this(position, expectedState, targetState, null);
    }

    public BlockMutation {
        Objects.requireNonNull(position);
        Objects.requireNonNull(expectedState);
        Objects.requireNonNull(targetState);
    }
}
