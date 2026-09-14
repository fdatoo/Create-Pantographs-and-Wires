package de.mrjulsen.paw.traction;

/**
 * Create builds every slope as a curved track edge, and a hand-driven train slows to its turning speed
 * on any curved edge. Scheduled trains already skip gentle straight slopes (Create's Navigation); this is
 * that same rule, so hand driving can use it too.
 */
public final class TrackSlopes {
    /** Rises below this are a flat curve, which is a real turn. */
    private static final double MIN_RISE = 1 / 16.0;
    /** How closely the two ends must face each other in plan to count as straight. */
    private static final double STRAIGHT_TOLERANCE = 1 / 64.0;
    /** Rise over length below which a straight slope is gentle. */
    private static final double MAX_GENTLE_RISE_PER_LENGTH = 0.225;

    private TrackSlopes() {}

    /**
     * @param startAy,startBy heights of the edge's two ends
     * @param axisA,axisB     the edge's end directions (x and z only), as Create stores them
     * @param length          the edge's length
     */
    public static boolean isGentleStraightSlope(double startAy, double startBy, double axisAx, double axisAz, double axisBx, double axisBz, double length) {
        double rise = Math.abs(startAy - startBy);
        if (rise <= MIN_RISE || length <= 0) {
            return false;
        }
        return isStraight(axisAx, axisAz, axisBx, axisBz) && rise / length < MAX_GENTLE_RISE_PER_LENGTH;
    }

    /** Whether an edge's two ends face each other in plan, so it runs straight when seen from above. */
    public static boolean isStraight(double axisAx, double axisAz, double axisBx, double axisBz) {
        double dx = axisAx + axisBx;
        double dz = axisAz + axisBz;
        return Math.sqrt(dx * dx + dz * dz) < STRAIGHT_TOLERANCE;
    }
}
