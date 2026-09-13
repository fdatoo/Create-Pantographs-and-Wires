package de.mrjulsen.paw.traction;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The centreline of a placed third rail: a cubic Bezier between two rail ends. Handles follow Create's
 * curved track (BezierConnection.determineHandles): a third of the distance for straight runs, a
 * gentler S-bend when parallel ends are offset, and a circular-arc approximation for turns. The curve
 * runs along the base of the rail blocks; the conductor sits {@link #CONDUCTOR_HEIGHT} above it.
 *
 * Unlike Create, a turn sizes each end's handle from that end's own leg to the corner. Create pads the
 * longer leg with straight track so both legs match; a rail is one curve, so with its legs unequal a
 * single shared handle would give a different curve depending on which end it was built from, and
 * the two ends' stored copies would disagree. Per-end handles make the curve identical from either
 * end and keep it between its legs. With equal legs they match Create exactly.
 *
 * Pure maths without Minecraft classes, so it can be tested.
 */
public final class ThirdRailCurve {
    /** Height of the conductor's centreline above the curve. */
    public static final double CONDUCTOR_HEIGHT = 4.5 / 16.0;
    /** Upper bound on how many pieces a curve is cut into, whatever its stored ends claim. */
    public static final int MAX_SEGMENTS = 4096;

    private static final double EPSILON = 1.0E-5;
    private static final int LENGTH_SAMPLES = 64;
    private static final Vector3dc UP = new Vector3d(0, 1, 0);

    private final Vector3d start1;
    private final Vector3d start2;
    private final Vector3d control1;
    private final Vector3d control2;
    private final double handleLength1;
    private final double handleLength2;
    /** Arc length from the start to parameter i / LENGTH_SAMPLES. */
    private final double[] cumulative = new double[LENGTH_SAMPLES + 1];

    /**
     * @param start1 where the curve leaves the first rail end
     * @param axis1  direction the curve leaves the first end in, pointing into the curve
     * @param start2 where the curve meets the second rail end
     * @param axis2  direction the curve leaves the second end in, also pointing into the curve
     */
    public ThirdRailCurve(Vector3dc start1, Vector3dc axis1, Vector3dc start2, Vector3dc axis2) {
        this.start1 = new Vector3d(start1);
        this.start2 = new Vector3d(start2);
        Vector3d a1 = new Vector3d(axis1).normalize();
        Vector3d a2 = new Vector3d(axis2).normalize();
        double[] handles = handleLengths(this.start1, this.start2, a1, a2);
        this.handleLength1 = handles[0];
        this.handleLength2 = handles[1];
        this.control1 = new Vector3d(a1).mul(handleLength1).add(this.start1);
        this.control2 = new Vector3d(a2).mul(handleLength2).add(this.start2);

        Vector3d previous = new Vector3d(this.start1);
        Vector3d current = new Vector3d();
        for (int i = 1; i <= LENGTH_SAMPLES; i++) {
            position(i / (double) LENGTH_SAMPLES, current);
            cumulative[i] = cumulative[i - 1] + current.distance(previous);
            previous.set(current);
        }
    }

    public double handleLength1() {
        return handleLength1;
    }

    public double handleLength2() {
        return handleLength2;
    }

    public double length() {
        return cumulative[LENGTH_SAMPLES];
    }

    public Vector3d position(double t, Vector3d dest) {
        double u = 1 - t;
        double b0 = u * u * u;
        double b1 = 3 * u * u * t;
        double b2 = 3 * u * t * t;
        double b3 = t * t * t;
        return dest.set(
            b0 * start1.x + b1 * control1.x + b2 * control2.x + b3 * start2.x,
            b0 * start1.y + b1 * control1.y + b2 * control2.y + b3 * start2.y,
            b0 * start1.z + b1 * control1.z + b2 * control2.z + b3 * start2.z
        );
    }

    public Vector3d derivative(double t, Vector3d dest) {
        double u = 1 - t;
        double d0 = 3 * u * u;
        double d1 = 6 * u * t;
        double d2 = 3 * t * t;
        return dest.set(
            d0 * (control1.x - start1.x) + d1 * (control2.x - control1.x) + d2 * (start2.x - control2.x),
            d0 * (control1.y - start1.y) + d1 * (control2.y - control1.y) + d2 * (start2.y - control2.y),
            d0 * (control1.z - start1.z) + d1 * (control2.z - control1.z) + d2 * (start2.z - control2.z)
        );
    }

    /** The curve parameter at a given arc length from the start, clamped to the curve. */
    public double parameterAtDistance(double distance) {
        double total = length();
        if (!(total > 0) || distance <= 0) {
            return 0;
        }
        if (distance >= total) {
            return 1;
        }
        int lo = 0;
        int hi = LENGTH_SAMPLES;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] <= distance) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = cumulative[hi] - cumulative[lo];
        double fraction = span <= 0 ? 0 : (distance - cumulative[lo]) / span;
        return (lo + fraction) / LENGTH_SAMPLES;
    }

    /**
     * How many pieces the curve splits into when no piece may be longer than maxSegmentLength, capped
     * at {@link #MAX_SEGMENTS} so a corrupt or hostile curve can't demand an enormous allocation.
     */
    public int segmentCount(double maxSegmentLength) {
        double pieces = Math.ceil(length() / maxSegmentLength);
        if (!(pieces >= 1)) {
            return 1;
        }
        return (int) Math.min(MAX_SEGMENTS, pieces);
    }

    /** segments + 1 parameters from 0 to 1, spaced evenly by arc length. */
    public double[] evenParameters(int segments) {
        segments = Math.max(1, Math.min(MAX_SEGMENTS, segments));
        double[] parameters = new double[segments + 1];
        double total = length();
        for (int i = 0; i <= segments; i++) {
            parameters[i] = parameterAtDistance(total * i / segments);
        }
        parameters[segments] = 1;
        return parameters;
    }

    /** The conductor's centreline as flattened vertices, no piece longer than maxSegmentLength. */
    public double[] conductor(double maxSegmentLength) {
        double[] parameters = evenParameters(segmentCount(maxSegmentLength));
        double[] vertices = new double[parameters.length * 3];
        Vector3d point = new Vector3d();
        for (int i = 0; i < parameters.length; i++) {
            position(parameters[i], point);
            vertices[i * 3] = point.x;
            vertices[i * 3 + 1] = point.y + CONDUCTOR_HEIGHT;
            vertices[i * 3 + 2] = point.z;
        }
        return vertices;
    }

    /** Handle lengths {at end 1, at end 2} for a curve between two ends. Swapping the ends swaps the result. */
    static double[] handleLengths(Vector3dc end1, Vector3dc end2, Vector3dc axis1, Vector3dc axis2) {
        Vector3d cross2 = new Vector3d(axis2).cross(UP);

        double a1 = Math.atan2(-axis2.z(), -axis2.x());
        double a2 = Math.atan2(axis1.z(), axis1.x());
        double angle = a1 - a2;

        double circle = 2 * Math.PI;
        angle = (angle + circle) % circle;
        if (Math.abs(circle - angle) < Math.abs(angle)) {
            angle = circle - angle;
        }

        if (nearlyEqual(angle, 0)) {
            double handle = end2.distance(end1) / 3;
            double[] intersect = intersect(end1, end2, axis1, cross2);
            if (intersect != null) {
                double t = Math.abs(intersect[0]);
                double u = Math.abs(intersect[1]);
                double min = Math.min(t, u);
                double max = Math.max(t, u);
                if (min > 1.2 && max / min > 1 && max / min < 3) {
                    handle = max - min;
                }
            }
            return new double[] {handle, handle};
        }

        // A circular arc turning through `angle` whose tangent legs are L long has radius
        // L / tan(angle / 2); Create's handle for that radius is radius * 4/3 * tan(angle / 4).
        double[] legs = intersect(end1, end2, axis1, axis2);
        double tanHalf = Math.tan(angle / 2);
        if (legs == null || !(Math.abs(tanHalf) > 1e-6)) {
            double handle = end2.distance(end1) / 3;
            return new double[] {handle, handle};
        }
        double factor = 4 / 3d * Math.tan(angle / 4) / tanHalf;
        return new double[] {handleOrOne(Math.abs(legs[0]) * factor), handleOrOne(Math.abs(legs[1]) * factor)};
    }

    private static double handleOrOne(double handle) {
        return nearlyEqual(handle, 0) || !Double.isFinite(handle) ? 1 : handle;
    }

    /**
     * Where the horizontal lines p1 + t r and p2 + u s cross, ignoring height: {t, u}, or null when
     * the lines are parallel. Matches Create's VecHelper.intersect on the Y plane.
     */
    static double[] intersect(Vector3dc p1, Vector3dc p2, Vector3dc r, Vector3dc s) {
        double qx = p2.x() - p1.x();
        double qz = p2.z() - p1.z();
        double rcs = r.x() * s.z() - r.z() * s.x();
        if (nearlyEqual(rcs, 0)) {
            return null;
        }
        double t = (qx * s.z() - qz * s.x()) / rcs;
        double u = (qx * r.z() - qz * r.x()) / rcs;
        return new double[] {t, u};
    }

    static boolean nearlyEqual(double a, double b) {
        return Math.abs(b - a) < EPSILON;
    }
}
