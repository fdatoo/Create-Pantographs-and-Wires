package de.mrjulsen.paw.traction.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TractionModeDetectorTest {
    private static final double CREATE_ACCELERATION = 0.0075;

    private static TractionMode run(TractionModeDetector detector, double start, double change, int ticks) {
        double speed = start;
        TractionMode mode = detector.mode();
        for (int i = 0; i < ticks; i++) {
            mode = detector.update(speed);
            speed += change;
        }
        return mode;
    }

    @Test
    void steadySpeedIsCoasting() {
        assertEquals(TractionMode.COAST, run(new TractionModeDetector(), 0.5, 0, 50));
    }

    @Test
    void createAccelerationIsPoweringWithinAFewTicks() {
        assertEquals(TractionMode.POWER, run(new TractionModeDetector(), 0.02, CREATE_ACCELERATION, 3));
    }

    @Test
    void createDecelerationIsBraking() {
        assertEquals(TractionMode.BRAKE, run(new TractionModeDetector(), 0.7, -CREATE_ACCELERATION, 3));
    }

    @Test
    void jitterStaysCoasting() {
        TractionModeDetector detector = new TractionModeDetector();
        for (int i = 0; i < 200; i++) {
            assertEquals(TractionMode.COAST, detector.update(0.5 + (i % 2 == 0 ? 0.0004 : -0.0004)));
        }
    }

    @Test
    void hysteresisHoldsPoweringUntilAccelerationFallsWell() {
        TractionModeDetector detector = new TractionModeDetector();
        run(detector, 0.02, CREATE_ACCELERATION, 20);
        double speed = 0.02 + 20 * CREATE_ACCELERATION;
        assertEquals(TractionMode.POWER, run(detector, speed, 0.0006, 40), "between exit and enter thresholds");
        assertEquals(TractionMode.COAST, run(detector, speed + 0.024, 0.0001, 40));
    }

    private static TractionMode cruise(TractionModeDetector detector, double grade, int ticks) {
        TractionMode mode = detector.mode();
        for (int i = 0; i < ticks; i++) {
            mode = detector.update(0.5, grade);
        }
        return mode;
    }

    @Test
    void climbingAtSteadySpeedIsPowering() {
        assertEquals(TractionMode.POWER, cruise(new TractionModeDetector(), 0.25, 20));
    }

    @Test
    void descendingAtSteadySpeedIsBraking() {
        assertEquals(TractionMode.BRAKE, cruise(new TractionModeDetector(), -0.25, 20));
    }

    @Test
    void aMetroGradeIsEnoughButAGentleOneIsNot() {
        // 4 %, a steep grade on a real metro, needs traction; 2 % stays below the threshold.
        assertEquals(TractionMode.POWER, cruise(new TractionModeDetector(), 0.04, 20));
        assertEquals(TractionMode.COAST, cruise(new TractionModeDetector(), 0.02, 20));
    }

    @Test
    void gravityCountsOnlyWhileMoving() {
        TractionModeDetector detector = new TractionModeDetector();
        for (int i = 0; i < 20; i++) {
            detector.update(0, 0.25);
        }
        assertEquals(TractionMode.COAST, detector.mode());
    }

    @Test
    void persistenceIgnoresABriefBlipWhileMoving() {
        TractionModeDetector quick = new TractionModeDetector();
        TractionModeDetector persistent = new TractionModeDetector();
        persistent.configure(4, 0.05);
        run(quick, 0.5, 0, 20);
        run(persistent, 0.5, 0, 20);
        boolean quickPowered = false;
        boolean persistentPowered = false;
        double[] blip = {0.5, 0.5075, 0.5075, 0.5075, 0.5075, 0.5075, 0.5075, 0.5075, 0.5075};
        for (double speed : blip) {
            quickPowered |= quick.update(speed) == TractionMode.POWER;
            persistentPowered |= persistent.update(speed) == TractionMode.POWER;
        }
        assertEquals(true, quickPowered, "without persistence a one-tick blip squeaks power in");
        assertEquals(false, persistentPowered);
    }

    @Test
    void persistenceDelaysARealChangeByItsTicks() {
        TractionModeDetector quick = new TractionModeDetector();
        TractionModeDetector persistent = new TractionModeDetector();
        persistent.configure(4, 0.05);
        run(quick, 0.5, 0, 20);
        run(persistent, 0.5, 0, 20);
        int quickTick = -1;
        int persistentTick = -1;
        double speed = 0.5;
        for (int tick = 0; tick < 20; tick++) {
            speed += CREATE_ACCELERATION;
            if (quick.update(speed) == TractionMode.POWER && quickTick < 0) quickTick = tick;
            if (persistent.update(speed) == TractionMode.POWER && persistentTick < 0) persistentTick = tick;
        }
        assertEquals(quickTick + 3, persistentTick, "the first tick counts toward the four");
    }

    @Test
    void departingFromRestIsNotDelayed() {
        TractionModeDetector quick = new TractionModeDetector();
        TractionModeDetector persistent = new TractionModeDetector();
        persistent.configure(4, 0.05);
        double speed = 0;
        for (int tick = 0; tick < 4; tick++) {
            assertEquals(quick.update(speed), persistent.update(speed), "tick " + tick);
            speed += CREATE_ACCELERATION;
        }
        assertEquals(TractionMode.POWER, persistent.mode());
    }

    @Test
    void reversingFromPowerToBrakeGoesStraightToBraking() {
        TractionModeDetector detector = new TractionModeDetector();
        run(detector, 0.02, CREATE_ACCELERATION, 20);
        TractionMode mode = TractionMode.POWER;
        double speed = 0.02 + 20 * CREATE_ACCELERATION;
        boolean sawCoast = false;
        for (int i = 0; i < 10; i++) {
            mode = detector.update(speed);
            sawCoast |= mode == TractionMode.COAST;
            speed -= CREATE_ACCELERATION;
        }
        assertEquals(TractionMode.BRAKE, mode);
    }
}
