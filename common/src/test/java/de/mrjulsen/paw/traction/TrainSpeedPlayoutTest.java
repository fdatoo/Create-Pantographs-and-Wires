package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class TrainSpeedPlayoutTest {
    private static final double ACCELERATION = 0.0075;
    private static final int CLIENT_OFFSET = 5;

    /** Create's default acceleration up to 0.7 blocks per tick, a cruise, then braking to rest. */
    private static double[] journey() {
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < 30; i++) v.add(0.0);
        while (v.get(v.size() - 1) < 0.7) v.add(Math.min(0.7, v.get(v.size() - 1) + ACCELERATION));
        for (int i = 0; i < 100; i++) v.add(0.7);
        while (v.get(v.size() - 1) > 0) v.add(Math.max(0, v.get(v.size() - 1) - ACCELERATION));
        for (int i = 0; i < 30; i++) v.add(0.0);
        return v.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** A grade that ramps steadily, so interpolation of it can be checked exactly. */
    private static double gradeAt(int serverTick) {
        return serverTick / 1000.0;
    }

    /**
     * Plays a journey whose report for server tick k arrives at client tick k + CLIENT_OFFSET + delay[k]
     * (a negative delay loses the report). Returns {speeds, grades} per client tick.
     */
    private static double[][] play(double[] speed, int[] delay) {
        TrainSpeedPlayout playout = new TrainSpeedPlayout();
        int end = speed.length + CLIENT_OFFSET + 40;
        List<List<Integer>> arriving = new ArrayList<>();
        for (int i = 0; i < end; i++) arriving.add(new ArrayList<>());
        for (int k = 0; k < speed.length; k++) {
            if (delay[k] >= 0) arriving.get(k + CLIENT_OFFSET + delay[k]).add(k);
        }
        double[][] out = new double[2][end];
        for (int now = 0; now < end; now++) {
            for (int k : arriving.get(now)) playout.offer(k, speed[k], gradeAt(k), false, now);
            playout.advance(now);
            out[0][now] = playout.speed();
            out[1][now] = playout.grade();
        }
        return out;
    }

    @Test
    void jitteryReportsPlayBackAsTheServersExactSeries() {
        double[] speed = journey();
        Random random = new Random(3);
        int[] choices = {1, 1, 1, 2, 2, 3};
        int[] delay = new int[speed.length];
        for (int k = 0; k < delay.length; k++) delay[k] = choices[random.nextInt(choices.length)];
        double[][] out = play(speed, delay);
        // Least delay 1 plus the playout delay of 3, once playback has drifted back after the departure.
        int lag = CLIENT_OFFSET + 1 + (int) TrainSpeedPlayout.DELAY_TICKS;
        for (int now = 160; now < speed.length + lag; now++) {
            assertEquals(speed[now - lag], out[0][now], 1e-9, "speed at client tick " + now);
            assertEquals(gradeAt(now - lag), out[1][now], 1e-9, "grade at client tick " + now);
        }
    }

    @Test
    void aLostOrVeryLateReportIsBridgedSmoothly() {
        double[] speed = journey();
        int[] delay = new int[speed.length];
        Arrays.fill(delay, 1);
        delay[250] = -1;   // lost while braking
        delay[251] = 30;   // far too late to use
        delay[252] = -1;
        double[][] out = play(speed, delay);
        int lag = CLIENT_OFFSET + 1 + (int) TrainSpeedPlayout.DELAY_TICKS;
        for (int now = 160; now < speed.length + lag; now++) {
            assertEquals(speed[now - lag], out[0][now], 1e-9, "linear across the gap, client tick " + now);
            assertEquals(gradeAt(now - lag), out[1][now], 1e-9);
        }
    }

    @Test
    void departingFromRestPlaysAtOnce() {
        double[] speed = journey();
        int[] delay = new int[speed.length];
        Arrays.fill(delay, 1);
        double[][] out = play(speed, delay);
        int firstMoving = 30;   // the journey rests for 30 ticks
        int arrives = firstMoving + CLIENT_OFFSET + 1;
        int steadyLag = CLIENT_OFFSET + 1 + (int) TrainSpeedPlayout.DELAY_TICKS;
        assertEquals(0, out[0][arrives - 1], 0);
        assertTrue(out[0][arrives + 1] > 0, "moving the tick after the first movement is reported");
        assertTrue(arrives + 1 < firstMoving + steadyLag, "sooner than the usual playout delay");
    }

    @Test
    void reportsGoStaleWhenTheyStop() {
        TrainSpeedPlayout playout = new TrainSpeedPlayout();
        assertFalse(playout.available(0));
        playout.offer(100, 0.4, 0.1, true, 0);
        playout.advance(0);
        assertTrue(playout.available(0));
        assertEquals(0.4, playout.speed(), 0);
        assertEquals(0.1, playout.grade(), 0);
        assertTrue(playout.throttleHeld());
        for (int now = 1; now <= TrainSpeedPlayout.STALE_AFTER_TICKS + 1; now++) {
            playout.advance(now);
        }
        assertFalse(playout.available(TrainSpeedPlayout.STALE_AFTER_TICKS + 1));
        assertEquals(0.4, playout.speed(), 0, "holds the newest report rather than inventing one");
    }

    @Test
    void sparseReportsAtRestInterpolate() {
        TrainSpeedPlayout playout = new TrainSpeedPlayout();
        for (int now = 0; now < 60; now++) {
            if (now % 20 == 0) playout.offer(now + 1000, 0, 0, false, now);
            playout.advance(now);
            assertEquals(0, playout.speed(), 0);
            assertTrue(playout.available(now));
        }
    }
}
