package de.mrjulsen.paw.traction;

import org.joml.Vector3dc;

/**
 * Whether a collector shoe on a moving carriage is touching a third rail.
 *
 * A shoe only picks up on its own side of the carriage: the rail's conductor has to pass through a
 * box that starts at the shoe block's centre and reaches outward, well downward (rails sit at track
 * level, below the carriage floor) and a little upward, over the shoe's length along the direction
 * of travel. Frames come from the contraption's rotation, so they follow the carriage through curves
 * and slopes.
 */
public final class CollectorShoeContact {
    /** How far outward from the shoe block's centre a conductor can be and still be picked up, in blocks. */
    public static final double REACH_OUTWARD = 2.5;
    /** How far below the shoe block's centre the conductor can be. Covers shoes mounted a floor above the bogies. */
    public static final double REACH_DOWN = 2.5;
    public static final double REACH_UP = 0.5;
    /** Half the shoe's length along the direction of travel. */
    public static final double HALF_LENGTH = 0.75;
    /** A world-space radius around the shoe that contains the whole contact box, for picking candidate rails. */
    public static final double QUERY_RADIUS = Math.sqrt(REACH_OUTWARD * REACH_OUTWARD + REACH_DOWN * REACH_DOWN + HALF_LENGTH * HALF_LENGTH);

    private CollectorShoeContact() {}

    /**
     * @param centre    the shoe block's centre in world space
     * @param outward   unit vector pointing away from the carriage, toward the side the shoe faces
     * @param up        unit vector pointing up out of the carriage floor
     * @param forward   unit vector along the carriage, perpendicular to the other two
     * @param conductor the rail conductor's centreline as flattened world-space vertices (x0, y0, z0, x1, ...)
     */
    public static boolean touches(Vector3dc centre, Vector3dc outward, Vector3dc up, Vector3dc forward, double[] conductor) {
        if (conductor.length < 6 || conductor.length % 3 != 0) {
            return false;
        }
        double[] local = new double[conductor.length];
        for (int i = 0; i < conductor.length; i += 3) {
            double dx = conductor[i] - centre.x();
            double dy = conductor[i + 1] - centre.y();
            double dz = conductor[i + 2] - centre.z();
            local[i] = dx * outward.x() + dy * outward.y() + dz * outward.z();
            local[i + 1] = dx * up.x() + dy * up.y() + dz * up.z();
            local[i + 2] = dx * forward.x() + dy * forward.y() + dz * forward.z();
        }
        return SegmentClipping.polylineIntersectsBox(
            local,
            0, -REACH_DOWN, -HALF_LENGTH,
            REACH_OUTWARD, REACH_UP, HALF_LENGTH
        );
    }
}
