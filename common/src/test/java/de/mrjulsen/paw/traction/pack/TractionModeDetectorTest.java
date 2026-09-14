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
