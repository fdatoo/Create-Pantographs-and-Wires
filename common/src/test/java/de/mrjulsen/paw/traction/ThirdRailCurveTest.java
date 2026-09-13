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
        // Create: radius * 4/3 * tan(pi / (2n)) with n = 4 for a quarter turn.
        double expected = 7.5 * 4 / 3d * Math.tan(Math.PI / 8);
        assertEquals(expected, quarterTurn().handleLength(), 1e-9);
    }

    @Test
    void quarterTurnIsAsLongAsTheArcItApproximates() {
        assertEquals(7.5 * Math.PI / 2, quarterTurn().length(), 0.02);
    }

    @Test
    void straightRailIsAStraightLineOfItsLength() {
        ThirdRailCurve straight = new ThirdRailCurve(new Vector3d(0.5, 64, 1), new Vector3d(0, 0, 1), new Vector3d(0.5, 64, 11), new Vector3d(0, 0, -1));
        assertEquals(10 / 3d, straight.handleLength(), TOLERANCE);
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
