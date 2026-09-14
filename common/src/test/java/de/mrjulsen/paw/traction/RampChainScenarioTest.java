package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.mrjulsen.paw.traction.pack.TractionMode;
import de.mrjulsen.paw.traction.pack.TractionModeDetector;

/**
 * A hand-driven train holding forward down a chain of short curving ramp pieces, like the test track's
 * spiral: each piece starts and ends level, and a short straight connector between pieces lifts Create's
 * 14 m/s curve limit for a moment. Once on the chain, the sound should brake steadily all the way down.
 */
class RampChainScenarioTest {
    static final double APPROACH = 60;
    static final double RAMP = 20;
    static final double DROP = 4;
    static final double CONNECTOR = 6;
    static final int RAMPS = 5;
    static final double CHAIN_END = APPROACH + RAMPS * RAMP + (RAMPS - 1) * CONNECTOR;

    private static final double CAP = 0.7;
    private static final double TOP = 1.05;
    private static final double ACCELERATION = 1.25 / 400;

    /** Height at horizontal distance s along the track. */
    static double height(double s) {
        if (s <= APPROACH) {
            return 0;
        }
        double at = APPROACH;
        double y = 0;
        for (int i = 0; i < RAMPS; i++) {
            if (s <= at + RAMP) {
                double t = (s - at) / RAMP;
                return y - DROP * (3 * t * t - 2 * t * t * t);
            }
            y -= DROP;
            at += RAMP;
            if (i < RAMPS - 1) {
                if (s <= at + CONNECTOR) {
                    return y;
                }
                at += CONNECTOR;
            }
        }
        return y;
    }

    static boolean onConnector(double s) {
        double at = APPROACH;
        for (int i = 0; i < RAMPS - 1; i++) {
            at += RAMP;
            if (s > at && s <= at + CONNECTOR) {
                return true;
            }
            at += CONNECTOR;
        }
        return false;
    }

    @Test
    void brakesSteadilyDownTheWholeChain() {
        TractionModeDetector detector = new TractionModeDetector();
        detector.configure(4, 10, 12, 0.05);   // the pack's persistence, as the sound manager configures it
        DistanceGrade grade = new DistanceGrade();
        List<String> changes = new ArrayList<>();
        double s = 0;
        double speed = CAP;
        TractionMode previous = detector.mode();
        TractionMode onChain = null;
        while (s < CHAIN_END + 40) {
            double cap = onConnector(s) ? TOP : CAP;
            speed = speed < cap ? Math.min(speed + ACCELERATION, cap) : Math.max(speed - ACCELERATION, cap);
            s += speed;
            TractionMode mode = detector.update(speed, grade.update(s, height(s), 0), true);
            if (s > APPROACH + RAMP && s < CHAIN_END) {
                onChain = mode;
                if (mode != previous) {
                    changes.add(String.format("%s -> %s at %.1f blocks", previous, mode, s));
                }
            }
            previous = mode;
        }
        assertEquals(List.of(), changes, "mode changes after the first ramp piece");
        assertEquals(TractionMode.BRAKE, onChain);
    }
}
