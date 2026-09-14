package de.mrjulsen.paw.traction.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SteadyLoadDetectorTest {
    private static final double TICK = 1 / 20.0;

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
            speed += 1.25 * TICK;
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
    void aClearChangeOfSpeedEndsSteadyAfterItsHold() {
        SteadyLoadDetector detector = new SteadyLoadDetector();
        for (int tick = 0; tick < 40; tick++) {
            detector.update(14, TractionMode.POWER);
        }
        assertEquals(1, detector.powerTarget(), 0);
        double speed = 14;
        speed += 1.0 * TICK;
        detector.update(speed, TractionMode.POWER);
        speed += 1.0 * TICK;
        detector.update(speed, TractionMode.POWER);
        assertEquals(1, detector.powerTarget(), 0, "two ticks of acceleration are not enough");
        speed += 1.0 * TICK;
        detector.update(speed, TractionMode.POWER);
        assertEquals(0, detector.powerTarget(), 0, "the third tick ends it");
    }

    @Test
    void accelerationBetweenTheThresholdsKeepsSteady() {
        SteadyLoadDetector detector = new SteadyLoadDetector();
        for (int tick = 0; tick < 40; tick++) {
            detector.update(14, TractionMode.POWER);
        }
        double speed = 14;
        for (int tick = 0; tick < 40; tick++) {
            speed += 0.1 * TICK;
            detector.update(speed, TractionMode.POWER);
        }
        assertEquals(1, detector.powerTarget(), 0);
    }

    @Test
    void losingTheFamilyOrTheSpeedRangeEndsSteadyAtOnce() {
        SteadyLoadDetector detector = new SteadyLoadDetector();
        for (int tick = 0; tick < 40; tick++) {
            detector.update(14, TractionMode.POWER);
        }
        detector.update(14, TractionMode.COAST);
        assertEquals(0, detector.powerTarget(), 0);

        SteadyLoadDetector slow = new SteadyLoadDetector();
        for (int tick = 0; tick < 100; tick++) {
            slow.update(4.9, TractionMode.BRAKE);
        }
        assertEquals(0, slow.brakeTarget(), 0, "below 5 m/s steady load never applies");
    }
}
