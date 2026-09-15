package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ElectricSupplyTest {
    private static final UUID TRAIN = UUID.randomUUID();

    @Test
    void aTrainWithoutContactIsNotSupplied() {
        assertFalse(new ElectricSupply().supplied(TRAIN));
    }

    @Test
    void supplyBridgesGapsUpToTheGraceTime() {
        ElectricSupply supply = new ElectricSupply();
        assertTrue(supply.record(TRAIN), "first contact starts supply");
        for (long tick = 1; tick <= ElectricSupply.GRACE_TICKS; tick++) {
            supply.tick();
            assertTrue(supply.supplied(TRAIN), "tick " + tick);
        }
        supply.tick();
        assertFalse(supply.supplied(TRAIN), "gone once the gap outlasts the grace time");
    }

    @Test
    void contactWithinTheGraceTimeIsNotANewStart() {
        ElectricSupply supply = new ElectricSupply();
        supply.record(TRAIN);
        for (int i = 0; i < 10; i++) {
            supply.tick();
        }
        assertFalse(supply.record(TRAIN));
        for (long i = 0; i <= ElectricSupply.GRACE_TICKS; i++) {
            supply.tick();
        }
        assertTrue(supply.record(TRAIN), "contact after a long gap starts supply again");
    }

    @Test
    void trainsAreIndependent() {
        ElectricSupply supply = new ElectricSupply();
        supply.record(TRAIN);
        assertFalse(supply.supplied(UUID.randomUUID()));
    }
}
