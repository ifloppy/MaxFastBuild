package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.WorldAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MutationResultAuditFlagsTest {
    @Test
    void parsesIndependentBreakAndPlaceAuditFlags() {
        WorldAccess.MutationResult both = new WorldAccess.MutationResult(true, "break_logged,place_event");
        assertTrue(both.breakAlreadyLogged());
        assertTrue(both.placeEventAlreadyLogged());

        WorldAccess.MutationResult breakOnly = new WorldAccess.MutationResult(true, "break_logged");
        assertTrue(breakOnly.breakAlreadyLogged());
        assertFalse(breakOnly.placeEventAlreadyLogged());

        WorldAccess.MutationResult placeOnly = new WorldAccess.MutationResult(true, "place_event");
        assertFalse(placeOnly.breakAlreadyLogged());
        assertTrue(placeOnly.placeEventAlreadyLogged());
    }
}
