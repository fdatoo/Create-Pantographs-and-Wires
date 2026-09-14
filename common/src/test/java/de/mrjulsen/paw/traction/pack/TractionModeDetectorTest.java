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

    private static final double MANUAL_ACCELERATION = 1.25 / 400;

    private static TractionModeDetector slowing(double grade, boolean held) {
        TractionModeDetector detector = new TractionModeDetector();
        double speed = 1.05;
        for (int i = 0; i < 20; i++) {
            detector.update(speed, grade, held);
            speed -= MANUAL_ACCELERATION;
        }
        return detector;
    }

    @Test
    void slowingForCreatesLimitWhileHoldingForwardIsNotBraking() {
        assertEquals(TractionMode.COAST, slowing(0, true).mode());
    }

    @Test
    void climbingWhileHeldBackIsPoweredByTheSlope() {
        TractionModeDetector detector = slowing(0.3, true);
        assertEquals(TractionMode.POWER, detector.mode());
        assertEquals(true, detector.slopeDriven());
    }

    @Test
    void lettingGoStillBrakes() {
        TractionModeDetector detector = slowing(0, false);
        assertEquals(TractionMode.BRAKE, detector.mode());
        assertEquals(false, detector.slopeDriven());
    }

    @Test
    void speedingUpOnTheFlatIsTheDriversPower() {
        TractionModeDetector detector = new TractionModeDetector();
        double speed = 0.2;
        for (int i = 0; i < 20; i++) {
            detector.update(speed, 0, true);
            speed += MANUAL_ACCELERATION;
        }
        assertEquals(TractionMode.POWER, detector.mode());
        assertEquals(false, detector.slopeDriven());
    }

    private static TractionModeDetector asInThePack() {
        TractionModeDetector detector = new TractionModeDetector();
        detector.configure(4, 10, 12, 0.05);   // 0.2 s brake, 0.5 s power, 0.6 s cruise, below 1 m/s at once
        return detector;
    }

    @Test
    void aShortForcedDipWhileHoldingKeepsPower() {
        TractionModeDetector detector = asInThePack();
        double speed = 0.3;
        for (int i = 0; i < 40; i++) {
            speed += MANUAL_ACCELERATION;
            detector.update(speed, 0, true);
        }
        assertEquals(TractionMode.POWER, detector.mode());
        boolean cruised = false;
        for (int i = 0; i < 16; i++) {   // a short curve: Create slows the train for 0.8 s
            speed -= MANUAL_ACCELERATION;
            cruised |= detector.update(speed, 0, true) == TractionMode.COAST;
        }
        for (int i = 0; i < 20; i++) {
            speed += MANUAL_ACCELERATION;
            cruised |= detector.update(speed, 0, true) == TractionMode.COAST;
        }
        assertEquals(false, cruised);
        assertEquals(TractionMode.POWER, detector.mode());
    }

    @Test
    void aLongForcedSlowdownStillSettlesToCruise() {
        TractionModeDetector detector = asInThePack();
        double speed = 0.3;
        for (int i = 0; i < 40; i++) {
            speed += MANUAL_ACCELERATION;
            detector.update(speed, 0, true);
        }
        for (int i = 0; i < 100; i++) {
            speed -= MANUAL_ACCELERATION;
            detector.update(speed, 0, true);
        }
        assertEquals(TractionMode.COAST, detector.mode());
    }

    @Test
    void aShortSpeedBlipDoesNotBringInPower() {
        TractionModeDetector detector = asInThePack();
        double speed = 0.7;
        for (int i = 0; i < 40; i++) {
            detector.update(speed, 0, true);
        }
        boolean powered = false;
        for (int i = 0; i < 6; i++) {   // Create lifts its cap for a moment between track pieces
            speed += MANUAL_ACCELERATION;
            powered |= detector.update(speed, 0, true) == TractionMode.POWER;
        }
        for (int i = 0; i < 20; i++) {
            speed = Math.max(0.7, speed - MANUAL_ACCELERATION);
            powered |= detector.update(speed, 0, true) == TractionMode.POWER;
        }
        assertEquals(false, powered);
    }

    @Test
    void aRealSpeedUpStillBringsInPower() {
        TractionModeDetector detector = asInThePack();
        double speed = 0.7;
        for (int i = 0; i < 40; i++) {
            detector.update(speed, 0, true);
        }
        for (int i = 0; i < 20; i++) {
            speed += MANUAL_ACCELERATION;
            detector.update(speed, 0, true);
        }
        assertEquals(TractionMode.POWER, detector.mode());
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
    void cruiseNeedsALongerHoldThanPowerOrBrake() {
        TractionModeDetector quick = new TractionModeDetector();
        TractionModeDetector held = new TractionModeDetector();
        quick.configure(4, 0.05);
        held.configure(4, 12, 0.05);
        double speed = 0.5;
        for (int i = 0; i < 20; i++) {
            quick.update(speed);
            held.update(speed);
            speed += CREATE_ACCELERATION;
        }
        int quickTick = -1;
        int heldTick = -1;
        for (int tick = 0; tick < 40; tick++) {
            if (quick.update(speed) == TractionMode.COAST && quickTick < 0) quickTick = tick;
            if (held.update(speed) == TractionMode.COAST && heldTick < 0) heldTick = tick;
        }
        assertEquals(quickTick + 8, heldTick);
    }

    private static boolean cruisesAtACrest(TractionModeDetector detector) {
        double speed = 0.7;
        for (int i = 0; i < 30; i++) {
            detector.update(speed, 0.2, true);   // climbing at Create's cap
        }
        assertEquals(TractionMode.POWER, detector.mode());
        boolean cruised = false;
        for (int i = 0; i < 10; i++) {
            cruised |= detector.update(speed, 0, true) == TractionMode.COAST;   // over the crest, cap still on
        }
        for (int i = 0; i < 20; i++) {
            speed += MANUAL_ACCELERATION;   // cap lifts, the train speeds up
            cruised |= detector.update(speed, 0, true) == TractionMode.COAST;
        }
        return cruised;
    }

    @Test
    void aSlopeHandingOverToSpeedingUpDoesNotDipToCruise() {
        TractionModeDetector quick = new TractionModeDetector();
        quick.configure(4, 0.05);
        assertEquals(true, cruisesAtACrest(quick), "with the short hold the power dips to cruise at the crest");
        TractionModeDetector held = new TractionModeDetector();
        held.configure(4, 12, 0.05);
        assertEquals(false, cruisesAtACrest(held));
        assertEquals(TractionMode.POWER, held.mode());
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
