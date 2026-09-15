package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpeedReadoutTest {
    @Test
    void showsZeroZeroAtRest() {
        SpeedReadout readout = new SpeedReadout();
        readout.update(0);
        assertEquals("0", readout.tens());
        assertEquals("0", readout.ones());
    }

    @Test
    void showsWholeMetresPerSecondWithALeadingZero() {
        SpeedReadout readout = new SpeedReadout();
        readout.update(4.2);
        assertEquals("0", readout.tens());
        assertEquals("4", readout.ones());
        readout.update(13.7);
        assertEquals("1", readout.tens());
        assertEquals("4", readout.ones());
    }

    @Test
    void aSpeedNearAHalfDoesNotFlicker() {
        SpeedReadout readout = new SpeedReadout();
        readout.update(14);
        for (int i = 0; i < 20; i++) {
            readout.update(i % 2 == 0 ? 13.45 : 14.55);
            assertEquals(14, readout.shown(), "update " + i);
        }
        readout.update(15);
        assertEquals(15, readout.shown());
    }

    @Test
    void comesBackToZeroWhenTheTrainStops() {
        SpeedReadout readout = new SpeedReadout();
        readout.update(1.2);
        assertEquals(1, readout.shown());
        readout.update(0);
        assertEquals(0, readout.shown());
    }

    @Test
    void reversingShowsTheSameSpeedAndOddInputsStayInRange() {
        SpeedReadout readout = new SpeedReadout();
        readout.update(-21);
        assertEquals(21, readout.shown());
        readout.update(250);
        assertEquals("9", readout.tens());
        assertEquals("9", readout.ones());
        readout.update(Double.NaN);
        assertEquals(0, readout.shown());
    }
}
