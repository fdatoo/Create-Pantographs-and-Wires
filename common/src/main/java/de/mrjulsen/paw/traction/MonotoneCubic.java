package de.mrjulsen.paw.traction;

/**
 * Monotone piecewise cubic Hermite interpolation through fixed knots, computed the way SciPy's
 * PchipInterpolator does it, so curves tuned offline reproduce exactly in game. It never
 * overshoots between knots, which matters for frequency curves that must not wobble past a
 * measured point. Outside the knot range the nearest endpoint value is held.
 */
public final class MonotoneCubic {
    private final double[] x;
    private final double[] y;
    private final double[] slopes;

    /** @param knots pairs of {position, value}, positions strictly increasing */
    public MonotoneCubic(double[][] knots) {
        if (knots.length < 2) {
            throw new IllegalArgumentException("A monotone cubic needs at least two knots");
        }
        x = new double[knots.length];
        y = new double[knots.length];
        for (int i = 0; i < knots.length; i++) {
            x[i] = knots[i][0];
            y[i] = knots[i][1];
            if (i > 0 && x[i] <= x[i - 1]) {
                throw new IllegalArgumentException("Knot positions must be strictly increasing");
            }
        }
        slopes = derivatives(x, y);
    }

    public double at(double position) {
        int last = x.length - 1;
        if (position <= x[0]) {
            return y[0];
        }
        if (position >= x[last]) {
            return y[last];
        }
        int lo = 0;
        int hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (x[mid] <= position) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double h = x[lo + 1] - x[lo];
        double s = (position - x[lo]) / h;
        double s2 = s * s;
        double s3 = s2 * s;
        return (2 * s3 - 3 * s2 + 1) * y[lo]
            + (s3 - 2 * s2 + s) * h * slopes[lo]
            + (-2 * s3 + 3 * s2) * y[lo + 1]
            + (s3 - s2) * h * slopes[lo + 1];
    }

    private static double[] derivatives(double[] x, double[] y) {
        int n = x.length;
        double[] h = new double[n - 1];
        double[] m = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            h[i] = x[i + 1] - x[i];
            m[i] = (y[i + 1] - y[i]) / h[i];
        }
        double[] d = new double[n];
        if (n == 2) {
            d[0] = m[0];
            d[1] = m[0];
            return d;
        }
        for (int k = 1; k < n - 1; k++) {
            double left = m[k - 1];
            double right = m[k];
            if (Math.signum(left) != Math.signum(right) || left == 0 || right == 0) {
                d[k] = 0;
            } else {
                // Weighted harmonic mean of the neighbouring slopes.
                double w1 = 2 * h[k] + h[k - 1];
                double w2 = h[k] + 2 * h[k - 1];
                d[k] = (w1 + w2) / (w1 / left + w2 / right);
            }
        }
        d[0] = edgeSlope(h[0], h[1], m[0], m[1]);
        d[n - 1] = edgeSlope(h[n - 2], h[n - 3], m[n - 2], m[n - 3]);
        return d;
    }

    /** Three-point end slope, limited so the first and last intervals stay monotone. */
    private static double edgeSlope(double h0, double h1, double m0, double m1) {
        double d = ((2 * h0 + h1) * m0 - h0 * m1) / (h0 + h1);
        if (Math.signum(d) != Math.signum(m0)) {
            return 0;
        }
        if (Math.signum(m0) != Math.signum(m1) && Math.abs(d) > 3 * Math.abs(m0)) {
            return 3 * m0;
        }
        return d;
    }
}
