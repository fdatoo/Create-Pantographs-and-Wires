package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SegmentClippingTest {

    private static boolean unitBox(double ax, double ay, double az, double bx, double by, double bz) {
        return SegmentClipping.segmentIntersectsBox(ax, ay, az, bx, by, bz, 0, 0, 0, 1, 1, 1);
    }

    @Test
    void segmentThroughTheBoxIntersects() {
        assertTrue(unitBox(-1, 0.5, 0.5, 2, 0.5, 0.5));
    }

    @Test
    void diagonalSegmentThroughACornerRegionIntersects() {
        assertTrue(unitBox(-0.5, -0.5, 0.5, 0.5, 0.5, 0.5));
    }

    @Test
    void segmentPassingBesideTheBoxMisses() {
        assertFalse(unitBox(-1, 1.5, 0.5, 2, 1.5, 0.5));
    }

    @Test
    void segmentThatStopsShortMisses() {
        assertFalse(unitBox(-3, 0.5, 0.5, -0.01, 0.5, 0.5));
    }

    @Test
    void segmentTouchingAFaceCounts() {
        assertTrue(unitBox(-1, 1, 0.5, 2, 1, 0.5));
    }

    @Test
    void segmentFullyInsideIntersects() {
        assertTrue(unitBox(0.2, 0.2, 0.2, 0.8, 0.8, 0.8));
    }

    @Test
    void skewSegmentMissingNearACornerMisses() {
        // Passes the (1, 1) corner on the outside.
        assertFalse(unitBox(0.5, 2, 0.5, 2, 0.5, 0.5));
    }

    @Test
    void zeroLengthSegmentIsAPoint() {
        assertTrue(unitBox(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        assertFalse(unitBox(1.5, 0.5, 0.5, 1.5, 0.5, 0.5));
    }

    @Test
    void polylineIntersectsWhenAnySegmentDoes() {
        double[] polyline = {-2, 0.5, 0.5, -1, 0.5, 0.5, 0.5, 0.5, 0.5, 3, 3, 3};
        assertTrue(SegmentClipping.polylineIntersectsBox(polyline, 0, 0, 0, 1, 1, 1));
    }

    @Test
    void polylineMissesWhenEverySegmentMisses() {
        double[] polyline = {-2, 0.5, 0.5, -1, 0.5, 0.5, -1, 3, 0.5};
        assertFalse(SegmentClipping.polylineIntersectsBox(polyline, 0, 0, 0, 1, 1, 1));
    }

    @Test
    void malformedPolylineMisses() {
        assertFalse(SegmentClipping.polylineIntersectsBox(new double[0], 0, 0, 0, 1, 1, 1));
        assertFalse(SegmentClipping.polylineIntersectsBox(new double[] {0.5, 0.5}, 0, 0, 0, 1, 1, 1));
    }
}
