package de.mrjulsen.paw.traction;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The grade a carriage is climbing or descending, averaged over the last {@link #WINDOW_BLOCKS} blocks it
 * travelled. Positive when climbing, whichever way the train runs.
 *
 * Create builds hills from ramp pieces that each start and end level, often joined by short straight
 * connectors, so a grade measured over a few ticks falls to nothing at every joint and the traction
 * sound hears the hill stop and start. Over about a carriage length the joints blend into the hill.
 */
public final class DistanceGrade {
    public static final double WINDOW_BLOCKS = 16;
    /** Travel below which a grade isn't trusted yet. */
    private static final double MIN_RUN = 2;
    private static final double MAX_GRADE = 4;
    private static final double STILL = 1e-6;

    /** {distance travelled, height} at recent positions, oldest first. */
    private final ArrayDeque<double[]> trail = new ArrayDeque<>();
    private boolean started;
    private double lastX;
    private double lastZ;
    private double travelled;
    private double grade;

    /** Records the carriage's position this tick and returns the grade. */
    public double update(double x, double y, double z) {
        if (started) {
            double step = Math.hypot(x - lastX, z - lastZ);
            if (step < STILL) {
                return grade;
            }
            travelled += step;
        }
        started = true;
        lastX = x;
        lastZ = z;
        trail.addLast(new double[] {travelled, y});

        double from = travelled - WINDOW_BLOCKS;
        // Keep one position at or before the start of the window, and everything after it.
        while (trail.size() > 2) {
            double[] oldest = trail.pollFirst();
            if (trail.peekFirst()[0] > from) {
                trail.addFirst(oldest);
                break;
            }
        }

        Iterator<double[]> positions = trail.iterator();
        double[] a = positions.next();
        double run;
        double rise;
        if (a[0] <= from && positions.hasNext()) {
            double[] b = positions.next();
            double yFrom = b[0] > a[0] ? a[1] + (b[1] - a[1]) * (from - a[0]) / (b[0] - a[0]) : a[1];
            run = WINDOW_BLOCKS;
            rise = y - yFrom;
        } else {
            run = travelled - a[0];
            rise = y - a[1];
        }
        if (run >= MIN_RUN) {
            grade = Math.max(-MAX_GRADE, Math.min(MAX_GRADE, rise / run));
        }
        return grade;
    }

    public double grade() {
        return grade;
    }
}
