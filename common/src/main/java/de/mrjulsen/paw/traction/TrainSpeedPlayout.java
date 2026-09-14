package de.mrjulsen.paw.traction;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plays back a train's speed and grade as the server reported them, a few ticks behind the newest
 * report.
 *
 * A client's own view of a train is no use for this: Create moves carriages on the client by chasing
 * position updates sent every few ticks, so the distance a carriage covers per client tick lurches
 * with every late or bunched packet. The server's figures are exact, but its reports arrive unevenly
 * too. Holding them in a short buffer and reading it at a steady one tick per tick turns them back
 * into the even series the server produced.
 *
 * Time is counted in client ticks by the caller ({@code now}), so jumps in the world clock don't matter.
 */
public final class TrainSpeedPlayout {
    /** How far playback runs behind the least-delayed report, in ticks: room for slower reports to arrive in time. */
    public static final double DELAY_TICKS = 3;
    /** Reports received within this many ticks set the estimate of the least network delay. */
    public static final int DELAY_WINDOW_TICKS = 40;
    /** With no report for this many ticks the playout counts as unavailable. */
    public static final int STALE_AFTER_TICKS = 30;
    /** Playback drifts toward its target position by at most this many ticks per tick, too little to hear. */
    public static final double MAX_DRIFT_PER_TICK = 0.05;
    /** A playhead this far from its target (a lag spike, a long gap) jumps instead of drifting. */
    public static final double RESYNC_TICKS = 20;

    private static final int SPEED = 0;
    private static final int GRADE = 1;

    /** Server tick to {speed, grade}. */
    private final TreeMap<Long, double[]> samples = new TreeMap<>();
    /** {client tick received, server tick minus client tick} for recent reports. */
    private final ArrayDeque<long[]> arrivals = new ArrayDeque<>();
    private double playhead = Double.NaN;
    private long lastAdvance = Long.MIN_VALUE;
    private long lastArrival = Long.MIN_VALUE;

    /**
     * Records a report received at client tick {@code now}.
     *
     * @param speed blocks per tick
     * @param grade rise over run along the direction of travel, positive when climbing
     */
    public void offer(long serverTick, double speed, double grade, long now) {
        if (!Double.isNaN(playhead) && serverTick < Math.floor(playhead)) {
            return;
        }
        samples.put(serverTick, new double[] {speed, grade});
        lastArrival = now;
        arrivals.addLast(new long[] {now, serverTick - now});
    }

    /** Moves playback on to client tick {@code now}. Calling it again for the same tick does nothing. */
    public void advance(long now) {
        if (now == lastAdvance) {
            return;
        }
        long elapsed = lastAdvance == Long.MIN_VALUE ? 0 : now - lastAdvance;
        lastAdvance = now;
        while (arrivals.size() > 1 && arrivals.peekFirst()[0] < now - DELAY_WINDOW_TICKS) {
            arrivals.removeFirst();
        }
        if (samples.isEmpty()) {
            return;
        }
        long leastDelayed = Long.MIN_VALUE;
        for (long[] arrival : arrivals) {
            leastDelayed = Math.max(leastDelayed, arrival[1]);
        }
        double target = now + leastDelayed - DELAY_TICKS;
        if (Double.isNaN(playhead) || Math.abs(target - (playhead + elapsed)) > RESYNC_TICKS) {
            playhead = target;
        } else {
            playhead += elapsed;
            playhead += Math.max(-MAX_DRIFT_PER_TICK, Math.min(MAX_DRIFT_PER_TICK, target - playhead));
        }
        // Out of reports: hold the newest rather than run ahead of it.
        playhead = Math.min(playhead, samples.lastKey());
        Long keep = samples.floorKey((long) Math.floor(playhead));
        if (keep != null) {
            samples.headMap(keep, false).clear();
        }
    }

    /** Speed at the playhead in blocks per tick, interpolated between reports. */
    public double speed() {
        return sample(SPEED);
    }

    /** Grade at the playhead, interpolated between reports. */
    public double grade() {
        return sample(GRADE);
    }

    private double sample(int index) {
        if (samples.isEmpty() || Double.isNaN(playhead)) {
            return 0;
        }
        Map.Entry<Long, double[]> below = samples.floorEntry((long) Math.floor(playhead));
        Map.Entry<Long, double[]> above = samples.ceilingEntry((long) Math.ceil(playhead));
        if (below == null) {
            return above.getValue()[index];
        }
        if (above == null || above.getKey().equals(below.getKey())) {
            return below.getValue()[index];
        }
        double t = (playhead - below.getKey()) / (above.getKey() - below.getKey());
        return below.getValue()[index] + (above.getValue()[index] - below.getValue()[index]) * t;
    }

    /** Whether reports are coming in, so the playout can be trusted. */
    public boolean available(long now) {
        return lastArrival != Long.MIN_VALUE && !Double.isNaN(playhead) && now - lastArrival <= STALE_AFTER_TICKS;
    }

    /** Whether nothing has been heard for {@code ticks}, so the playout can be forgotten. */
    public boolean silentFor(long now, long ticks) {
        return lastArrival == Long.MIN_VALUE || now - lastArrival > ticks;
    }
}
