package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrackSlopesTest {
    @Test
    void aGentleStraightSlopeIsNotATurn() {
        // Rising 4 over 48 along x: the ends face each other in plan.
        assertTrue(TrackSlopes.isGentleStraightSlope(64, 68, 1, 0, -1, 0, 48.2));
    }

    @Test
    void aSteepSlopeStillCounts() {
        assertFalse(TrackSlopes.isGentleStraightSlope(64, 72, 1, 0, -1, 0, 33));
    }

    @Test
    void aCurveThatAlsoRisesStillCounts() {
        assertFalse(TrackSlopes.isGentleStraightSlope(64, 66, 1, 0, 0, -1, 40));
    }

    @Test
    void aFlatCurveStillCounts() {
        assertFalse(TrackSlopes.isGentleStraightSlope(64, 64, 1, 0, -1, 0, 20));
    }
}
