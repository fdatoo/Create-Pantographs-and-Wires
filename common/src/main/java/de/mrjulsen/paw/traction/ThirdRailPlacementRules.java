package de.mrjulsen.paw.traction;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Whether a third rail can be laid between two rail blocks, and which way each end faces. The rules
 * are Create's track placement rules (TrackPlacement.tryConnect) for flat track, so a rail curve is
 * allowed exactly where a track curve of the same shape would be: no turns under 60 degrees or too
 * tight, no sloped S-bends, no slopes too steep. Rails are always flat at their ends, so Create's
 * rules for leaving sloped track don't apply. Problems are Create's own message keys
 * ("create.track." + problem).
 *
 * Curves are also sized the way Create sizes track: a compact curve with straight rail leading into
 * it, unless the turn is maximised (Create's track does that while the sprint key is held), which
 * spreads the curve over all the room there is. Either way a turn whose ends are unequally far from the
 * corner gets a straight lead on the longer side, so the curve itself stays symmetrical. A rail laid the
 * same way as its track therefore follows it.
 */
public final class ThirdRailPlacementRules {

    /**
     * @param problem  Create's track message suffix, or null when valid
     * @param hasCurve whether the ends below describe a curve worth previewing; false for problems
     *                 Create reports without drawing a curve
     * @param end1     where the curve starts, after any straight rail leading into it
     * @param step1    the first block's unnormalised axis, pointing towards the curve: straight rail
     *                 blocks go at the first block plus 1 to extent1 steps
     * @param extent1  how many straight rail blocks lead from the first block to the curve
     */
    public record Outcome(boolean valid, String problem, boolean hasCurve, Vector3d end1, Vector3d axis1, Vector3d end2, Vector3d axis2,
        Vector3d step1, Vector3d step2, int extent1, int extent2) {
        private static Outcome rejected(String problem) {
            return new Outcome(false, problem, false, null, null, null, null, null, null, 0, 0);
        }

        /** Whether straight rail leads into the curve that a maximised curve would take up instead. */
        public boolean hasStraights() {
            return extent1 > 0 || extent2 > 0;
        }
    }

    private ThirdRailPlacementRules() {}

    /** Where a curve leaves a rail block: the centre of its base, half a block along its (unnormalised) axis. */
    public static Vector3d curveStart(int x, int y, int z, Vector3dc axis) {
        return new Vector3d(x + 0.5 + axis.x() * 0.5, y, z + 0.5 + axis.z() * 0.5);
    }

    /** A block's axis turned to point the way the player is looking. */
    public static Vector3d alongLook(Vector3dc blockAxis, Vector3dc look) {
        Vector3d axis = new Vector3d(blockAxis);
        return look.dot(axis) < 0 ? axis.negate() : axis;
    }

    /** Evaluates a maximised curve, filling all the room between the two blocks. */
    public static Outcome evaluate(int x1, int y1, int z1, Vector3dc axis1, int x2, int y2, int z2, Vector3dc axis2, int maxLength) {
        return evaluate(x1, y1, z1, axis1, x2, y2, z2, axis2, maxLength, true);
    }

    /**
     * @param axis1     the first block's unnormalised axis, as it was selected
     * @param axis2     the second block's unnormalised axis, pointing along the player's look
     * @param maxLength the furthest the two blocks may be apart, in blocks
     * @param maximise  whether the curve takes up all the room, instead of Create's compact size with straight rail leading in
     */
    public static Outcome evaluate(int x1, int y1, int z1, Vector3dc axis1, int x2, int y2, int z2, Vector3dc axis2, int maxLength, boolean maximise) {
        if (x1 == x2 && y1 == y2 && z1 == z2) {
            return Outcome.rejected("second_point");
        }
        long dx = x2 - x1;
        long dy = y2 - y1;
        long dz = z2 - z1;
        if (dx * dx + dy * dy + dz * dz > (long) maxLength * maxLength) {
            return Outcome.rejected("too_far");
        }

        Vector3d a1 = new Vector3d(axis1);
        Vector3d a2 = new Vector3d(axis2);
        Vector3d end1 = curveStart(x1, y1, z1, a1);
        Vector3d end2 = curveStart(x2, y2, z2, a2);

        if (a1.dot(new Vector3d(end2).sub(end1)) < 0) {
            a1.negate();
            end1 = curveStart(x1, y1, z1, a1);
        }

        Vector3d n1 = new Vector3d(a1).normalize();
        Vector3d n2 = new Vector3d(a2).normalize();
        double[] intersect = ThirdRailCurve.intersect(end1, end2, n1, n2);
        boolean parallel = intersect == null;

        if ((parallel && n1.dot(n2) > 0) || (!parallel && (intersect[0] < 0 || intersect[1] < 0))) {
            a2.negate();
            n2.negate();
            end2 = curveStart(x2, y2, z2, a2);
        }

        Vector3d cross2 = new Vector3d(n2).cross(0, 1, 0);
        double angle = Math.atan2(n2.z, n2.x) - Math.atan2(n1.z, n1.x);
        double ascend = end2.y - end1.y;
        double absAscend = Math.abs(ascend);

        final Vector3d finalEnd1 = end1;
        final Vector3d finalEnd2 = end2;
        java.util.function.Function<String, Outcome> withCurve =
            problem -> new Outcome(false, problem, true, finalEnd1, n1, finalEnd2, n2, new Vector3d(a1), new Vector3d(a2), 0, 0);

        int extent1 = 0;
        int extent2 = 0;

        // Straight or S-bend
        boolean straight = false;
        double centreDistance = 0;
        if (parallel) {
            double[] sTest = ThirdRailCurve.intersect(end1, end2, n1, cross2);
            if (sTest != null) {
                double t = Math.abs(sTest[0]);
                double u = Math.abs(sTest[1]);
                straight = ThirdRailCurve.nearlyEqual(u, 0);

                if (!straight && sTest[0] < 0) {
                    return Outcome.rejected("perpendicular");
                }
                if (straight) {
                    centreDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                } else {
                    if (!ThirdRailCurve.nearlyEqual(ascend, 0)) {
                        return withCurve.apply("ascending_s_curve");
                    }
                    double targetT = u <= 1 ? 3 : u * 2;
                    if (t < targetT) {
                        return withCurve.apply("too_sharp");
                    }
                    // Create's standard S-bend length, with the rest as straight rail at both ends
                    if (t > targetT && !maximise) {
                        int correction = (int) ((t - targetT) / a1.length());
                        extent1 = correction / 2 + correction % 2;
                        extent2 = correction / 2;
                    }
                }
            }
        }

        // Straight ramp
        if (straight && !ThirdRailCurve.nearlyEqual(ascend, 0)) {
            double horizontalBlocks = Math.round((centreDistance + 1) / a1.length());
            double minimum = Math.max(absAscend < 4 ? absAscend * 4 : absAscend * 3, 6) / a1.length();
            if (horizontalBlocks < minimum) {
                return withCurve.apply("too_steep");
            }
            // Create's shortest ramp for the height, with the rest as straight rail at both ends
            if (horizontalBlocks > minimum && !maximise) {
                int correction = (int) (horizontalBlocks - minimum);
                extent1 = correction / 2 + correction % 2;
                extent2 = correction / 2;
            }
        }

        // Turn
        if (!parallel) {
            double absAngle = Math.abs(Math.toDegrees(angle));
            if (absAngle < 60 || absAngle > 300) {
                return Outcome.rejected("turn_90");
            }

            intersect = ThirdRailCurve.intersect(end1, end2, n1, n2);
            if (intersect[0] < 0 || intersect[1] < 0) {
                return Outcome.rejected("too_sharp");
            }
            double dist1 = Math.abs(intersect[0]);
            double dist2 = Math.abs(intersect[1]);
            // The end further from the corner leads in straight, so the curve is symmetrical.
            double lead1 = dist1 > dist2 ? (dist1 - dist2) / a1.length() : 0;
            double lead2 = dist2 > dist1 ? (dist2 - dist1) / a2.length() : 0;
            double turnSize = Math.min(dist1, dist2) - .1d;
            boolean ninety = (absAngle + .25f) % 90 < 1;
            double minTurnSize = ninety ? 7 : 3.25;
            double turnSizeToFitAscend =
                minTurnSize + (ninety ? Math.max(0, absAscend - 3) * 2f : Math.max(0, absAscend - 1.5f) * 1.5f);

            if (turnSize < minTurnSize) {
                return withCurve.apply("too_sharp");
            }
            if (turnSize < turnSizeToFitAscend) {
                return withCurve.apply("too_steep");
            }
            // Create's standard turn size, with the rest as straight rail at both ends
            if (!maximise) {
                lead1 += (turnSize - turnSizeToFitAscend) / a1.length();
                lead2 += (turnSize - turnSizeToFitAscend) / a2.length();
            }
            extent1 = (int) Math.floor(lead1);
            extent2 = (int) Math.floor(lead2);
        }

        Vector3d curveEnd1 = new Vector3d(a1).mul(extent1).add(end1);
        Vector3d curveEnd2 = new Vector3d(a2).mul(extent2).add(end2);
        return new Outcome(true, null, true, curveEnd1, n1, curveEnd2, n2, new Vector3d(a1), new Vector3d(a2), extent1, extent2);
    }
}
