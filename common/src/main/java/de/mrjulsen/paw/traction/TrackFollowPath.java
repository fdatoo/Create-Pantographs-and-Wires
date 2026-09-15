package de.mrjulsen.paw.traction;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;

/**
 * Turns a track's centreline into a third rail's path beside it: every point is moved sideways (to the
 * right of the direction of travel) and up by offsets that blend from the start values to the end values,
 * so the path starts and ends exactly at its two rail blocks. Points are then spaced evenly again, since
 * the inside of a bend is shorter than the centreline.
 *
 * Pure maths without Minecraft classes, so it can be tested.
 */
public final class TrackFollowPath {
    /** Distance between the points of a stored rail path. */
    public static final double SPACING = 0.5;

    private TrackFollowPath() {}

    /**
     * How far a point is to the right of a line through a centreline point, looking along its tangent.
     * Right is the tangent crossed with up; negative is left.
     */
    public static double side(Vector3d point, Vector3d on, Vector3d tangent) {
        Vector3d right = right(tangent);
        return (point.x - on.x) * right.x + (point.z - on.z) * right.z;
    }

    /** The horizontal unit tangent at a point of a polyline, from its neighbours. */
    public static Vector3d tangentAt(List<Vector3d> points, int index) {
        int before = Math.max(0, index - 1);
        int after = Math.min(points.size() - 1, index + 1);
        Vector3d tangent = new Vector3d(points.get(after)).sub(points.get(before));
        tangent.y = 0;
        double length = tangent.length();
        return length > 1e-9 ? tangent.div(length) : new Vector3d(0, 0, 1);
    }

    /**
     * @param centreline the track's centreline from the first rail block to the second, densely sampled
     * @param side1      the first rail block's distance to the right of the track
     * @param side2      the second's
     * @param up1        how far the first rail block's base is above the track
     * @param up2        the second's
     * @return the rail path as flattened x, y, z points, SPACING apart
     */
    public static double[] offset(List<Vector3d> centreline, double side1, double side2, double up1, double up2) {
        List<Vector3d> dense = resample(centreline, SPACING / 2);
        double total = length(dense);
        List<Vector3d> moved = new ArrayList<>(dense.size());
        double travelled = 0;
        for (int i = 0; i < dense.size(); i++) {
            if (i > 0) {
                travelled += dense.get(i).distance(dense.get(i - 1));
            }
            double blend = total > 0 ? travelled / total : 0;
            double side = side1 + (side2 - side1) * blend;
            double up = up1 + (up2 - up1) * blend;
            Vector3d right = right(tangentAt(dense, i));
            moved.add(new Vector3d(dense.get(i)).add(right.x * side, up, right.z * side));
        }
        List<Vector3d> even = resample(moved, SPACING);
        double[] flat = new double[even.size() * 3];
        for (int i = 0; i < even.size(); i++) {
            flat[i * 3] = even.get(i).x;
            flat[i * 3 + 1] = even.get(i).y;
            flat[i * 3 + 2] = even.get(i).z;
        }
        return flat;
    }

    /** Points along a polyline no more than spacing apart, keeping both ends. */
    public static List<Vector3d> resample(List<Vector3d> points, double spacing) {
        List<Vector3d> result = new ArrayList<>();
        if (points.isEmpty()) {
            return result;
        }
        double total = length(points);
        int pieces = Math.max(1, (int) Math.ceil(total / spacing));
        double step = total / pieces;
        result.add(new Vector3d(points.get(0)));
        int segment = 0;
        double segmentStart = 0;
        for (int i = 1; i < pieces; i++) {
            double target = step * i;
            while (segment < points.size() - 2 && segmentStart + points.get(segment).distance(points.get(segment + 1)) < target) {
                segmentStart += points.get(segment).distance(points.get(segment + 1));
                segment++;
            }
            Vector3d a = points.get(segment);
            Vector3d b = points.get(Math.min(points.size() - 1, segment + 1));
            double span = a.distance(b);
            double fraction = span > 0 ? (target - segmentStart) / span : 0;
            result.add(new Vector3d(a).lerp(b, Math.max(0, Math.min(1, fraction))));
        }
        if (points.size() > 1) {
            result.add(new Vector3d(points.get(points.size() - 1)));
        }
        return result;
    }

    public static double length(List<Vector3d> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += points.get(i).distance(points.get(i - 1));
        }
        return total;
    }

    private static Vector3d right(Vector3d tangent) {
        return new Vector3d(-tangent.z, 0, tangent.x);
    }
}
