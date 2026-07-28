package dev.maxfastbuild.api;

import java.util.Objects;

public record BlockMutation(BlockPos position, String expectedState, String targetState) {
    public BlockMutation {
        Objects.requireNonNull(position);
        Objects.requireNonNull(expectedState);
        Objects.requireNonNull(targetState);
    }
}
