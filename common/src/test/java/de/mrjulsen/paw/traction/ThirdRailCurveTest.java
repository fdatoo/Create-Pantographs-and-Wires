package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ThirdRailCurveTest {

    private static final double TOLERANCE = 1e-9;

    /** A quarter turn of radius 7.5, heading south from (0.5, 0, 1) and ending heading east at (8, 0, 8.5). */
    private static ThirdRailCurve quarterTurn() {
        return new ThirdRailCurve(new Vector3d(0.5, 0, 1), new Vector3d(0, 0, 1), new Vector3d(8, 0, 8.5), new Vector3d(-1, 0, 0));
    }

    @Test
    void curveStartsAndEndsAtItsEnds() {
        ThirdRailCurve curve = quarterTurn();
        Vector3d p = new Vector3d();
        curve.position(0, p);
        assertEquals(0.5, p.x, TOLERANCE);
        assertEquals(1, p.z, TOLERANCE);
        curve.position(1, p);
        assertEquals(8, p.x, TOLERANCE);
        assertEquals(8.5, p.z, TOLERANCE);
    }

    @Test
    void curveLeavesEachEndAlongItsAxis() {
        ThirdRailCurve curve = quarterTurn();
        Vector3d d = new Vector3d();
        curve.derivative(0, d).normalize();
        assertEquals(0, d.x, TOLERANCE);
        assertEquals(1, d.z, TOLERANCE);
        // At the far end the curve arrives travelling against that end's axis.
        curve.derivative(1, d).normalize();
        assertEquals(1, d.x, TOLERANCE);
        assertEquals(0, d.z, TOLERANCE);
    }

    @Test
    void quarterTurnUsesCreatesCircularArcHandle() {
        // Create: radius * 4/3 * tan(pi / (2n)) with n = 4 for a quarter turn. Equal legs give equal handles.
        double expected = 7.5 * 4 / 3d * Math.tan(Math.PI / 8);
        assertEquals(expected, quarterTurn().handleLength1(), 1e-9);
        assertEquals(expected, quarterTurn().handleLength2(), 1e-9);
    }

    /** Laid between rail blocks at (0, 64, 0) running east and (24, 64, 8) running south: legs of 23.5 and 7.5. */
    private static ThirdRailCurve unequalTurn(boolean fromFirstEnd) {
        Vector3d end1 = new Vector3d(1, 64, 0.5);
        Vector3d axis1 = new Vector3d(1, 0, 0);
        Vector3d end2 = new Vector3d(24.5, 64, 8);
        Vector3d axis2 = new Vector3d(0, 0, -1);
        return fromFirstEnd ? new ThirdRailCurve(end1, axis1, end2, axis2) : new ThirdRailCurve(end2, axis2, end1, axis1);
    }

    @Test
    void curveIsTheSameFromEitherEnd() {
        ThirdRailCurve forward = unequalTurn(true);
        ThirdRailCurve backward = unequalTurn(false);
        assertEquals(forward.length(), backward.length(), 1e-9);
        Vector3d a = new Vector3d();
        Vector3d b = new Vector3d();
        for (int i = 0; i <= 20; i++) {
            double t = i / 20d;
            forward.position(t, a);
            backward.position(1 - t, b);
            assertEquals(0, a.distance(b), 1e-9, "at t=" + t);
        }
        assertEquals(forward.handleLength1(), backward.handleLength2(), 1e-9);
    }

    @Test
    void unequalTurnStaysBetweenItsLegs() {
        ThirdRailCurve curve = unequalTurn(true);
        Vector3d p = new Vector3d();
        for (int i = 0; i <= 200; i++) {
            curve.position(i / 200d, p);
            assertTrue(p.z >= 0.5 - 1e-9, "dips behind the first leg at z=" + p.z);
            assertTrue(p.x <= 24.5 + 1e-9, "overshoots the second leg at x=" + p.x);
        }
    }

    @Test
    void absurdlyLongCurveIsCutIntoABoundedNumberOfPieces() {
        ThirdRailCurve huge = new ThirdRailCurve(new Vector3d(0, 0, 0), new Vector3d(1, 0, 0), new Vector3d(1e10, 0, 0), new Vector3d(-1, 0, 0));
        assertEquals(ThirdRailCurve.MAX_SEGMENTS, huge.segmentCount(0.25));
        assertEquals((ThirdRailCurve.MAX_SEGMENTS + 1) * 3, huge.conductor(0.25).length);
    }

    @Test
    void quarterTurnIsAsLongAsTheArcItApproximates() {
        assertEquals(7.5 * Math.PI / 2, quarterTurn().length(), 0.02);
    }

    @Test
    void straightRailIsAStraightLineOfItsLength() {
        ThirdRailCurve straight = new ThirdRailCurve(new Vector3d(0.5, 64, 1), new Vector3d(0, 0, 1), new Vector3d(0.5, 64, 11), new Vector3d(0, 0, -1));
        assertEquals(10 / 3d, straight.handleLength1(), TOLERANCE);
        assertEquals(10 / 3d, straight.handleLength2(), TOLERANCE);
        assertEquals(10, straight.length(), 1e-6);
        Vector3d p = new Vector3d();
        for (int i = 0; i <= 10; i++) {
            straight.position(i / 10d, p);
            assertEquals(0.5, p.x, TOLERANCE);
        }
    }

    @Test
    void evenParametersAreEvenlySpacedAlongTheCurve() {
        ThirdRailCurve curve = quarterTurn();
        double[] parameters = curve.evenParameters(20);
        assertEquals(21, parameters.length);
        assertEquals(0, parameters[0], TOLERANCE);
        assertEquals(1, parameters[20], TOLERANCE);
        Vector3d previous = new Vector3d();
        Vector3d current = new Vector3d();
        curve.position(parameters[0], previous);
        double expectedStep = curve.length() / 20;
        for (int i = 1; i <= 20; i++) {
            assertTrue(parameters[i] > parameters[i - 1]);
            curve.position(parameters[i], current);
            assertEquals(expectedStep, current.distance(previous), expectedStep * 0.05);
            previous.set(current);
        }
    }

    @Test
    void conductorRunsAboveTheCurveWithShortPieces() {
        ThirdRailCurve curve = quarterTurn();
        double[] conductor = curve.conductor(0.5);
        int vertices = conductor.length / 3;
        assertEquals(curve.segmentCount(0.5) + 1, vertices);
        assertEquals(ThirdRailCurve.CONDUCTOR_HEIGHT, conductor[1], TOLERANCE);
        for (int i = 1; i < vertices; i++) {
            double dx = conductor[i * 3] - conductor[(i - 1) * 3];
            double dz = conductor[i * 3 + 2] - conductor[(i - 1) * 3 + 2];
            assertTrue(Math.sqrt(dx * dx + dz * dz) <= 0.5 + 1e-3);
        }
    }

    @Test
    void parameterAtDistanceClampsToTheCurve() {
        ThirdRailCurve curve = quarterTurn();
        assertEquals(0, curve.parameterAtDistance(-3), TOLERANCE);
        assertEquals(1, curve.parameterAtDistance(curve.length() + 3), TOLERANCE);
    }

    @Test
    void coincidentEndsDoNotBreakSampling() {
        ThirdRailCurve point = new ThirdRailCurve(new Vector3d(1, 2, 3), new Vector3d(0, 0, 1), new Vector3d(1, 2, 3), new Vector3d(0, 0, -1));
        assertEquals(0, point.length(), TOLERANCE);
        assertEquals(6, point.conductor(0.5).length);
    }
}
