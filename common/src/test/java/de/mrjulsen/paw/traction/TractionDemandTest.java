package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TractionDemandTest {

    /** Create's default train acceleration, in blocks per tick squared. */
    private static final double CREATE_ACCELERATION = 0.0075;

    private static TractionDemand run(double startSpeed, double changePerTick, int ticks) {
        TractionDemand demand = new TractionDemand();
        double speed = startSpeed;
        for (int i = 0; i < ticks; i++) {
            demand.update(speed);
            speed += changePerTick;
        }
        return demand;
    }

    @Test
    void steadySpeedIsCoasting() {
        TractionDemand demand = run(0.5, 0, 100);
        assertEquals(0, demand.power(), 1e-12);
        assertEquals(0, demand.brake(), 1e-12);
    }

    @Test
    void firstTickHasNoDemandWhateverTheSpeed() {
        TractionDemand demand = new TractionDemand();
        demand.update(0.7);
        assertEquals(0, demand.power(), 1e-12);
        assertEquals(0, demand.brake(), 1e-12);
    }

    @Test
    void acceleratingBuildsFullPowerDemand() {
        TractionDemand demand = run(0, CREATE_ACCELERATION, 60);
        assertTrue(demand.power() > 0.99, "power " + demand.power());
        assertEquals(0, demand.brake(), 1e-12);
    }

    @Test
    void gentleDecelerationIsCoastingNotBraking() {
        TractionDemand demand = run(0.7, -TractionDemand.DEAD_ZONE / 2, 100);
        assertEquals(0, demand.brake(), 1e-12);
        assertEquals(0, demand.power(), 1e-12);
    }

    @Test
    void brakingBuildsBrakeDemand() {
        TractionDemand demand = run(0.7, -CREATE_ACCELERATION, 60);
        assertTrue(demand.brake() > 0.99, "brake " + demand.brake());
        assertEquals(0, demand.power(), 1e-12);
    }

    @Test
    void powerEasesOffGraduallyOnceAccelerationStops() {
        TractionDemand demand = run(0, CREATE_ACCELERATION, 60);
        double speed = 60 * CREATE_ACCELERATION;
        demand.update(speed);
        assertTrue(demand.power() > 0.8, "a tick after stopping, power " + demand.power());
        for (int i = 0; i < 80; i++) {
            demand.update(speed);
        }
        assertTrue(demand.power() < 0.05, "after four seconds, power " + demand.power());
    }

    @Test
    void jitterWithinTheDeadZoneNeverRegistersAsDemand() {
        TractionDemand demand = new TractionDemand();
        for (int i = 0; i < 200; i++) {
            demand.update(0.5 + (i % 2 == 0 ? 0.0003 : -0.0003));
        }
        assertTrue(demand.power() < 1e-9 && demand.brake() < 1e-9, "power " + demand.power() + " brake " + demand.brake());
    }
}
