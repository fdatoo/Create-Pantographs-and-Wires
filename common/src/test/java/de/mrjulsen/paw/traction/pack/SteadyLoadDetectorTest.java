package de.mrjulsen.paw.traction.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SteadyLoadDetectorTest {
    private static final double TICK = 1 / 20.0;
    /** One tick of Create's train acceleration at 1.25 m/s². */
    private static final double CREATE_STEP = 1.25 * TICK;

    private static SteadyLoadDetector steadyAt(double speed, TractionMode mode) {
        SteadyLoadDetector detector = new SteadyLoadDetector();
        for (int tick = 0; tick < 40; tick++) {
            detector.update(speed, mode);
        }
        return detector;
    }

    @Test
    void holdingPowerAtAConstantSpeedBecomesSteadyAfterTheHoldTime() {
        SteadyLoadDetector detector = new SteadyLoadDetector();
        for (int tick = 0; tick < 24; tick++) {
            detector.update(14, TractionMode.POWER);
            assertEquals(0, detector.powerTarget(), 0, "tick " + tick);
        }
        detector.update(14, TractionMode.POWER);
        assertEquals(1, detector.powerTarget(), 0, "after 1.25 s");
        assertEquals(0, detector.brakeTarget(), 0);
    }

    @Test
    void coastingBeforehandDoesNotCountTowardTheHold() {
        SteadyLoadDetector detector = new SteadyLoadDetector();
        for (int tick = 0; tick < 100; tick++) {
            detector.update(14, TractionMode.COAST);
        }
        for (int tick = 0; tick < 24; tick++) {
            detector.update(14, TractionMode.BRAKE);
        }
        assertEquals(0, detector.brakeTarget(), 0);
        detector.update(14, TractionMode.BRAKE);
        assertEquals(1, detector.brakeTarget(), 0);
    }

    @Test
    void acceleratingIsNeverSteady() {
        SteadyLoadDetector detector = new SteadyLoadDetector();
        double speed = 5;
        for (int tick = 0; tick < 200; tick++) {
            detector.update(speed, TractionMode.POWER);
            speed += CREATE_STEP;
            assertEquals(0, detector.powerTarget(), 0, "tick " + tick);
        }
    }

    @Test
    void theHoldStartsWhenTheSpeedSettlesAfterAccelerating() {
        SteadyLoadDetector detector = new SteadyLoadDetector();
        for (double speed = 5; speed < 14; speed += 1.0 * TICK) {
            detector.update(speed, TractionMode.POWER);
        }
        detector.update(14, TractionMode.POWER);
        for (int tick = 0; tick < 24; tick++) {
            detector.update(14, TractionMode.POWER);
            assertEquals(0, detector.powerTarget(), 0, "tick " + tick);
        }
        detector.update(14, TractionMode.POWER);
        assertEquals(1, detector.powerTarget(), 0, "1.25 s after settling");
    }

    @Test
    void curveCapLiftsAtTrackJoinsKeepSteady() {
        // As on the logged descent: held at the 14 m/s turning cap, the cap lifts for a few ticks at a join,
        // the speed steps up at Create's acceleration and is pulled back to the cap.
        SteadyLoadDetector detector = steadyAt(14, TractionMode.BRAKE);
        for (int lift = 1; lift <= 7; lift++) {
            double speed = 14;
            for (int tick = 0; tick < lift; tick++) {
                speed += CREATE_STEP;
                detector.update(speed, TractionMode.BRAKE);
                assertEquals(1, detector.brakeTarget(), 0, lift + "-tick lift, rising tick " + tick);
            }
            while (speed > 14) {
                speed = Math.max(14, speed - CREATE_STEP);
                detector.update(speed, TractionMode.BRAKE);
                assertEquals(1, detector.brakeTarget(), 0, lift + "-tick lift, falling");
            }
            for (int tick = 0; tick < 10; tick++) {
                detector.update(14, TractionMode.BRAKE);
            }
        }
        assertEquals(1, detector.brakeTarget(), 0);
    }

    @Test
    void aRealChangeOfSpeedEndsSteadyWithinHalfASecond() {
        SteadyLoadDetector detector = steadyAt(14, TractionMode.POWER);
        double speed = 14;
        int ticks = 0;
        while (detector.powerTarget() == 1) {
            speed += CREATE_STEP;
            detector.update(speed, TractionMode.POWER);
            ticks++;
            assertTrue(ticks <= 10, "still steady at " + speed + " m/s");
        }
        assertEquals(8, ticks, "0.5 m/s from the held speed at 1.25 m/s² takes 8 ticks");
    }

    @Test
    void aSlowDriftKeepsSteady() {
        SteadyLoadDetector detector = steadyAt(14, TractionMode.POWER);
        double speed = 14;
        for (int tick = 0; tick < 400; tick++) {
            speed += 0.1 * TICK;
            detector.update(speed, TractionMode.POWER);
        }
        assertEquals(1, detector.powerTarget(), 0, "2 m/s of drift under the exit acceleration");
        for (int tick = 0; tick < 8; tick++) {
            speed += CREATE_STEP;
            detector.update(speed, TractionMode.POWER);
        }
        assertEquals(0, detector.powerTarget(), 0, "a clear change still ends it from the new held speed");
    }

    @Test
    void losingTheFamilyOrTheSpeedRangeEndsSteadyAtOnce() {
        SteadyLoadDetector detector = steadyAt(14, TractionMode.POWER);
        detector.update(14, TractionMode.COAST);
        assertEquals(0, detector.powerTarget(), 0);

        SteadyLoadDetector brake = steadyAt(14, TractionMode.BRAKE);
        brake.update(14, TractionMode.POWER);
        assertEquals(0, brake.brakeTarget(), 0, "a family change");

        SteadyLoadDetector slow = new SteadyLoadDetector();
        for (int tick = 0; tick < 100; tick++) {
            slow.update(4.9, TractionMode.BRAKE);
        }
        assertEquals(0, slow.brakeTarget(), 0, "below 5 m/s steady load never applies");
    }

    @Test
    void configuringEveryTickKeepsTheWindow() {
        SteadyLoadDetector detector = steadyAt(14, TractionMode.POWER);
        double speed = 14;
        for (int tick = 0; tick < 8; tick++) {
            detector.configure(PackSettings.Steady.defaults());
            speed += CREATE_STEP;
            detector.update(speed, TractionMode.POWER);
        }
        assertEquals(0, detector.powerTarget(), 0);
    }
}
