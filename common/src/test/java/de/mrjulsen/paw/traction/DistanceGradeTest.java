package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DistanceGradeTest {
    @Test
    void aSteadySlopeIsMeasuredExactly() {
        DistanceGrade grade = new DistanceGrade();
        double last = 0;
        for (int i = 0; i < 100; i++) {
            double x = i * 0.5;
            last = grade.update(x, 0.1 * x, 0);
        }
        assertEquals(0.1, last, 1e-9);
    }

    @Test
    void climbingIsPositiveWhicheverWayTheTrainRuns() {
        DistanceGrade grade = new DistanceGrade();
        double last = 0;
        for (int i = 0; i < 100; i++) {
            last = grade.update(-i * 0.5, i * 0.5 * 0.2, 0);
        }
        assertEquals(0.2, last, 1e-9);
    }

    @Test
    void levelJointsBetweenRampPiecesBlendIntoTheHill() {
        DistanceGrade grade = new DistanceGrade();
        double steepest = 0;
        double shallowest = Double.MAX_VALUE;
        double shallowestInstant = Double.MAX_VALUE;
        double previousY = 0;
        for (double s = 0.7; s < RampChainScenarioTest.CHAIN_END; s += 0.7) {
            double y = RampChainScenarioTest.height(s);
            double g = grade.update(s, y, 0);
            if (s > RampChainScenarioTest.APPROACH + DistanceGrade.WINDOW_BLOCKS) {
                steepest = Math.max(steepest, -g);
                shallowest = Math.min(shallowest, -g);
                shallowestInstant = Math.min(shallowestInstant, -(y - previousY) / 0.7);
            }
            previousY = y;
        }
        assertTrue(shallowestInstant < 0.001, "the joints themselves are level: " + shallowestInstant);
        assertTrue(shallowest > 0.05, "averaged over distance the hill never flattens out: " + shallowest);
        assertTrue(steepest < 0.3, "and never exaggerates it: " + steepest);
    }

    @Test
    void standingStillKeepsTheLastGrade() {
        DistanceGrade grade = new DistanceGrade();
        for (int i = 0; i < 40; i++) {
            grade.update(i * 0.5, i * 0.5 * 0.1, 0);
        }
        double moving = grade.grade();
        for (int i = 0; i < 40; i++) {
            assertEquals(moving, grade.update(19.5, 1.95, 0), 0);
        }
    }
}
