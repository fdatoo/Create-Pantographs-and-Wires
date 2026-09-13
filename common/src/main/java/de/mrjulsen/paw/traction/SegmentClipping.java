package de.mrjulsen.paw.traction;

/**
 * Whether straight segments pass through an axis-aligned box, found by clipping each segment's
 * parameter range against the box one axis at a time. Boxes are closed: touching a face counts.
 */
public final class SegmentClipping {
    private SegmentClipping() {}

    public static boolean segmentIntersectsBox(
        double ax, double ay, double az,
        double bx, double by, double bz,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ
    ) {
        double[] range = {0, 1};
        return clip(ax, bx - ax, minX, maxX, range)
            && clip(ay, by - ay, minY, maxY, range)
            && clip(az, bz - az, minZ, maxZ, range);
    }

    /**
     * @param xyz polyline vertices flattened as x0, y0, z0, x1, y1, z1, ...; a single vertex is
     *            treated as a point
     */
    public static boolean polylineIntersectsBox(
        double[] xyz,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ
    ) {
        if (xyz.length < 3 || xyz.length % 3 != 0) {
            return false;
        }
        if (xyz.length == 3) {
            return segmentIntersectsBox(xyz[0], xyz[1], xyz[2], xyz[0], xyz[1], xyz[2], minX, minY, minZ, maxX, maxY, maxZ);
        }
        for (int i = 3; i < xyz.length; i += 3) {
            if (segmentIntersectsBox(
                xyz[i - 3], xyz[i - 2], xyz[i - 1], xyz[i], xyz[i + 1], xyz[i + 2],
                minX, minY, minZ, maxX, maxY, maxZ
            )) {
                return true;
            }
        }
        return false;
    }

    private static boolean clip(double origin, double delta, double min, double max, double[] range) {
        if (delta == 0) {
            return origin >= min && origin <= max;
        }
        double t0 = (min - origin) / delta;
        double t1 = (max - origin) / delta;
        if (t0 > t1) {
            double swap = t0;
            t0 = t1;
            t1 = swap;
        }
        range[0] = Math.max(range[0], t0);
        range[1] = Math.min(range[1], t1);
        return range[0] <= range[1];
    }
}
