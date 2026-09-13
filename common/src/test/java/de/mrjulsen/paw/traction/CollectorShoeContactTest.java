package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CollectorShoeContactTest {

    private static final Vector3d CENTRE = new Vector3d(10.5, 65.5, 20.5);
    private static final Vector3d OUTWARD_EAST = new Vector3d(1, 0, 0);
    private static final Vector3d UP = new Vector3d(0, 1, 0);
    private static final Vector3d FORWARD_SOUTH = new Vector3d(0, 0, 1);

    /** A straight conductor running along z at the given x and y, from z0 to z1. */
    private static double[] alongZ(double x, double y, double z0, double z1) {
        return new double[] {x, y, z0, x, y, z1};
    }

    private static boolean eastShoeTouches(double[] conductor) {
        return CollectorShoeContact.touches(CENTRE, OUTWARD_EAST, UP, FORWARD_SOUTH, conductor);
    }

    @Test
    void railBelowAndOutsideTheShoeIsPickedUp() {
        // Rail one block outward and a block and a bit down, as with a shoe mounted at bogey level.
        assertTrue(eastShoeTouches(alongZ(11.5, 64.3, 10, 30)));
    }

    @Test
    void railOnTheOtherSideOfTheCarriageIsIgnored() {
        assertFalse(eastShoeTouches(alongZ(9.5, 64.3, 10, 30)));
    }

    @Test
    void railBeyondReachOutwardIsIgnored() {
        assertFalse(eastShoeTouches(alongZ(CENTRE.x + CollectorShoeContact.REACH_OUTWARD + 0.1, 64.3, 10, 30)));
    }

    @Test
    void railTooFarBelowIsIgnored() {
        assertFalse(eastShoeTouches(alongZ(11.5, CENTRE.y - CollectorShoeContact.REACH_DOWN - 0.1, 10, 30)));
    }

    @Test
    void railAboveTheShoeIsIgnored() {
        assertFalse(eastShoeTouches(alongZ(11.5, CENTRE.y + CollectorShoeContact.REACH_UP + 0.1, 10, 30)));
    }

    @Test
    void railThatEndsBeforeTheShoeIsIgnored() {
        assertFalse(eastShoeTouches(alongZ(11.5, 64.3, 0, CENTRE.z - CollectorShoeContact.HALF_LENGTH - 0.1)));
    }

    @Test
    void railEndingUnderTheShoeStillCounts() {
        assertTrue(eastShoeTouches(alongZ(11.5, 64.3, 0, CENTRE.z)));
    }

    @Test
    void curvedRailCrossingTheContactZoneIsPickedUp() {
        double[] curve = {
            14, 64.3, 16,
            12.5, 64.3, 19,
            11.8, 64.3, 21,
            11.6, 64.3, 25
        };
        assertTrue(eastShoeTouches(curve));
    }

    @Test
    void frameRotatesWithTheCarriage() {
        // Carriage running along x with the shoe facing north: the rail must be north of it.
        Vector3d outwardNorth = new Vector3d(0, 0, -1);
        Vector3d forwardEast = new Vector3d(1, 0, 0);
        double[] northRail = {0, 64.3, 19.5, 20, 64.3, 19.5};
        double[] southRail = {0, 64.3, 21.5, 20, 64.3, 21.5};
        assertTrue(CollectorShoeContact.touches(CENTRE, outwardNorth, UP, forwardEast, northRail));
        assertFalse(CollectorShoeContact.touches(CENTRE, outwardNorth, UP, forwardEast, southRail));
    }

    @Test
    void queryRadiusContainsTheWholeContactBox() {
        double farthest = Math.sqrt(
            Math.pow(CollectorShoeContact.REACH_OUTWARD, 2)
                + Math.pow(Math.max(CollectorShoeContact.REACH_DOWN, CollectorShoeContact.REACH_UP), 2)
                + Math.pow(CollectorShoeContact.HALF_LENGTH, 2));
        assertTrue(CollectorShoeContact.QUERY_RADIUS >= farthest - 1e-12);
    }

    @Test
    void conductorWithFewerThanTwoVerticesNeverTouches() {
        assertFalse(eastShoeTouches(new double[] {11.5, 64.3, 20.5}));
        assertFalse(eastShoeTouches(new double[0]));
    }
}
