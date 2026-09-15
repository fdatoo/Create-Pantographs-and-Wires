package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class TrackFollowPathTest {

    private static List<Vector3d> straightNorthToSouth(double length) {
        List<Vector3d> points = new ArrayList<>();
        for (int i = 0; i <= length * 4; i++) {
            points.add(new Vector3d(0.5, 64, i / 4.0));
        }
        return points;
    }

    /** A quarter circle of the given radius around (0, 64, 0), from +x heading south to +z heading west. */
    private static List<Vector3d> quarterTurn(double radius) {
        List<Vector3d> points = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            double a = Math.PI / 2 * i / 200;
            points.add(new Vector3d(radius * Math.cos(a), 64, radius * Math.sin(a)));
        }
        return points;
    }

    @Test
    void rightOfSouthboundTravelIsWest() {
        Vector3d south = new Vector3d(0, 0, 1);
        assertEquals(2, TrackFollowPath.side(new Vector3d(-2, 64, 5), new Vector3d(0, 64, 5), south), 1e-9);
        assertEquals(-2, TrackFollowPath.side(new Vector3d(2, 64, 5), new Vector3d(0, 64, 5), south), 1e-9);
    }

    @Test
    void aStraightTrackGivesAParallelRailEvenlySpaced() {
        double[] path = TrackFollowPath.offset(straightNorthToSouth(20), 2, 2, 0, 0);
        assertEquals(41, path.length / 3);
        for (int i = 0; i < path.length / 3; i++) {
            assertEquals(-1.5, path[i * 3], 1e-6, "x at point " + i);
            assertEquals(64, path[i * 3 + 1], 1e-9);
            assertEquals(i * 0.5, path[i * 3 + 2], 1e-6);
        }
    }

    @Test
    void aRailBesideATurnKeepsItsDistanceAllTheWayRound() {
        // Travelling from +x round to +z the turn bends right, so a rail on the left is on the outside.
        double[] path = TrackFollowPath.offset(quarterTurn(12), -2, -2, 0, 0);
        for (int i = 2; i < path.length / 3 - 2; i++) {
            double radius = Math.hypot(path[i * 3], path[i * 3 + 2]);
            assertEquals(14, radius, 0.02, "point " + i);
        }
        for (int i = 1; i < path.length / 3; i++) {
            double gap = Math.hypot(path[i * 3] - path[(i - 1) * 3], path[i * 3 + 2] - path[(i - 1) * 3 + 2]);
            assertTrue(gap <= TrackFollowPath.SPACING + 1e-6, "gap " + gap);
        }
    }

    @Test
    void offsetsBlendSoTheRailMeetsBothBlocks() {
        double[] path = TrackFollowPath.offset(straightNorthToSouth(10), 2, 2.5, 0, 1);
        assertEquals(-1.5, path[0], 1e-6);
        assertEquals(64, path[1], 1e-9);
        int last = path.length - 3;
        assertEquals(-2, path[last], 1e-6);
        assertEquals(65, path[last + 1], 1e-9);
        assertEquals(10, path[last + 2], 1e-6);
    }
}
