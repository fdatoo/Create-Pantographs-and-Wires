package de.mrjulsen.paw.traction.pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * One layer's pitch and volume against train speed, interpolated linearly between rows. Outside its
 * first and last speed a layer is silent; both end speeds are included.
 */
public final class LayerCurve {
    private final double[] speed;
    private final double[] pitch;
    private final double[] volume;

    /** @param points rows of {speed in m/s, pitch multiplier, volume}, in any order */
    public LayerCurve(List<double[]> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("a curve needs at least one row");
        }
        List<double[]> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(p -> p[0]));
        speed = new double[sorted.size()];
        pitch = new double[sorted.size()];
        volume = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            speed[i] = sorted.get(i)[0];
            pitch[i] = sorted.get(i)[1];
            volume[i] = sorted.get(i)[2];
        }
    }

    public double firstSpeed() {
        return speed[0];
    }

    public double lastSpeed() {
        return speed[speed.length - 1];
    }

    public double firstPitch() {
        return pitch[0];
    }

    /**
     * Pitch and volume at a speed.
     *
     * @param out receives {pitch, volume}
     * @return false, leaving out untouched, when the speed is outside the curve
     */
    public boolean sample(double speedMps, double[] out) {
        int last = speed.length - 1;
        if (!(speedMps >= speed[0] && speedMps <= speed[last])) {
            return false;
        }
        int found = Arrays.binarySearch(speed, speedMps);
        if (found >= 0) {
            out[0] = pitch[found];
            out[1] = volume[found];
            return true;
        }
        int hi = -found - 1;
        int lo = hi - 1;
        double t = (speedMps - speed[lo]) / (speed[hi] - speed[lo]);
        out[0] = pitch[lo] + (pitch[hi] - pitch[lo]) * t;
        out[1] = volume[lo] + (volume[hi] - volume[lo]) * t;
        return true;
    }
}
