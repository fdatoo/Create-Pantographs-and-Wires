package de.mrjulsen.paw.traction;

/**
 * Converts a point into the listener's own frame, for sounds played relative to the listener: x to the
 * right, y up, and z behind (OpenAL's convention, looking along -z). A relative sound placed this way
 * pans and attenuates as the world point would, and scaling it toward zero draws it into the centre
 * of the listener's head.
 */
public final class HeadRelativePosition {
    private HeadRelativePosition() {}

    /**
     * @param dx,dy,dz offset from the listener to the point, in world axes
     * @param look     unit vector the listener faces
     * @param up       unit vector above the listener
     * @param left     unit vector to the listener's left
     * @param scale    1 for the true position, 0 for the centre of the head
     * @param out      receives {x, y, z}
     */
    public static void toListener(double dx, double dy, double dz, double[] look, double[] up, double[] left, double scale, double[] out) {
        out[0] = -(dx * left[0] + dy * left[1] + dz * left[2]) * scale;
        out[1] = (dx * up[0] + dy * up[1] + dz * up[2]) * scale;
        out[2] = -(dx * look[0] + dy * look[1] + dz * look[2]) * scale;
    }
}
