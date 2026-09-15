package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import de.mrjulsen.paw.traction.ThirdRailPlacementRules.Outcome;

class ThirdRailPlacementRulesTest {

    private static final Vector3d Z = new Vector3d(0, 0, 1);
    private static final Vector3d X = new Vector3d(1, 0, 0);
    private static final Vector3d PD = new Vector3d(1, 0, 1);
    private static final int MAX = 32;

    @Test
    void samePointAsksForASecondPoint() {
        Outcome outcome = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 0, 64, 0, Z, MAX);
        assertFalse(outcome.valid());
        assertEquals("second_point", outcome.problem());
        assertFalse(outcome.hasCurve());
    }

    @Test
    void endsTooFarApartAreRejected() {
        Outcome outcome = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 0, 64, 40, Z, MAX);
        assertEquals("too_far", outcome.problem());
    }

    @Test
    void straightRunIsValidAndFacesInward() {
        Outcome outcome = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 0, 64, 10, Z, MAX);
        assertTrue(outcome.valid());
        assertNull(outcome.problem());
        assertEquals(0.5, outcome.end1().x, 1e-9);
        assertEquals(1, outcome.end1().z, 1e-9);
        assertEquals(10, outcome.end2().z, 1e-9);
        assertEquals(1, outcome.axis1().z, 1e-9);
        assertEquals(-1, outcome.axis2().z, 1e-9);
    }

    @Test
    void selectionPointingAwayIsTurnedAround() {
        // Selected while looking north, then the second point is south of the first.
        Outcome outcome = ThirdRailPlacementRules.evaluate(0, 64, 0, new Vector3d(0, 0, -1), 0, 64, 10, Z, MAX);
        assertTrue(outcome.valid());
        assertEquals(1, outcome.axis1().z, 1e-9);
        assertEquals(1, outcome.end1().z, 1e-9);
    }

    @Test
    void gentleRampIsValid() {
        assertTrue(ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 0, 65, 10, Z, MAX).valid());
    }

    @Test
    void steepRampIsRejected() {
        Outcome outcome = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 0, 67, 8, Z, MAX);
        assertEquals("too_steep", outcome.problem());
        assertTrue(outcome.hasCurve());
    }

    @Test
    void gentleSBendIsValid() {
        assertTrue(ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 1, 64, 12, Z, MAX).valid());
    }

    @Test
    void sharpSBendIsRejected() {
        assertEquals("too_sharp", ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 4, 64, 6, Z, MAX).problem());
    }

    @Test
    void slopedSBendIsRejected() {
        assertEquals("ascending_s_curve", ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 1, 65, 12, Z, MAX).problem());
    }

    @Test
    void wideQuarterTurnIsValid() {
        // Looking south-east from the first block; the far end's axis gets turned to face the curve.
        Outcome outcome = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 8, 64, 8, X, MAX);
        assertTrue(outcome.valid());
        assertEquals(8, outcome.end2().x, 1e-9);
        assertEquals(8.5, outcome.end2().z, 1e-9);
        assertEquals(-1, outcome.axis2().x, 1e-9);
    }

    @Test
    void tightQuarterTurnIsRejected() {
        assertEquals("too_sharp", ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 4, 64, 4, X, MAX).problem());
    }

    @Test
    void wideBendOntoADiagonalIsValidLikeTrack() {
        // Heading south, then south-east: Create allows track to bend onto a diagonal.
        Outcome outcome = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 12, 64, 20, PD, MAX);
        assertTrue(outcome.valid());
        assertEquals(-Math.sqrt(0.5), outcome.axis2().x, 1e-9);
        assertEquals(-Math.sqrt(0.5), outcome.axis2().z, 1e-9);
    }

    @Test
    void turnBeyondNinetyDegreesIsRejected() {
        // Heading south, then arriving heading north-east: a 135 degree turn.
        assertEquals("turn_90", ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 20, 64, 8, new Vector3d(-1, 0, 1), MAX).problem());
    }

    @Test
    void aCompactQuarterTurnLeadsInStraightLikeTrack() {
        // Ends 11.5 blocks from the corner; Create's standard 90 degree turn is 7, the rest is straight rail.
        Outcome compact = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 12, 64, 12, X, MAX, false);
        assertTrue(compact.valid());
        assertEquals(4, compact.extent1());
        assertEquals(4, compact.extent2());
        assertEquals(5, compact.end1().z, 1e-9);
        assertEquals(8, compact.end2().x, 1e-9);
        assertTrue(compact.hasStraights());

        Outcome maximised = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 12, 64, 12, X, MAX, true);
        assertTrue(maximised.valid());
        assertEquals(0, maximised.extent1());
        assertEquals(0, maximised.extent2());
        assertEquals(1, maximised.end1().z, 1e-9);
    }

    @Test
    void aLopsidedTurnLeadsInOnItsLongerSideEvenWhenMaximised() {
        Outcome outcome = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 8, 64, 12, X, MAX, true);
        assertTrue(outcome.valid());
        assertEquals(4, outcome.extent1());
        assertEquals(0, outcome.extent2());
    }

    @Test
    void aCompactSBendLeadsInStraightAtBothEnds() {
        Outcome compact = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 1, 64, 12, Z, MAX, false);
        assertTrue(compact.valid());
        assertEquals(4, compact.extent1());
        assertEquals(4, compact.extent2());
        assertFalse(ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 1, 64, 12, Z, MAX, true).hasStraights());
    }

    @Test
    void aFlatStraightNeverNeedsLeadIns() {
        Outcome outcome = ThirdRailPlacementRules.evaluate(0, 64, 0, Z, 0, 64, 20, Z, MAX, false);
        assertTrue(outcome.valid());
        assertFalse(outcome.hasStraights());
    }

    @Test
    void alongLookFlipsAnAxisPointingBehindThePlayer() {
        Vector3d look = new Vector3d(0.2, -0.3, -0.9);
        assertEquals(-1, ThirdRailPlacementRules.alongLook(Z, look).z, 1e-9);
        assertEquals(1, ThirdRailPlacementRules.alongLook(new Vector3d(0, 0, -1), look).z * -1, 1e-9);
    }

    @Test
    void diagonalCurveStartIsTheBlockCorner() {
        Vector3d start = ThirdRailPlacementRules.curveStart(3, 70, 5, PD);
        assertEquals(4, start.x, 1e-9);
        assertEquals(70, start.y, 1e-9);
        assertEquals(6, start.z, 1e-9);
    }
}
