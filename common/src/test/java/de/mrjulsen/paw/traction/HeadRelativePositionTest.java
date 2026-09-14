package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HeadRelativePositionTest {
    // Minecraft's camera facing south (+z): its left vector is east (+x).
    private static final double[] LOOK_SOUTH = {0, 0, 1};
    private static final double[] UP = {0, 1, 0};
    private static final double[] LEFT_WHEN_SOUTH = {1, 0, 0};

    @Test
    void aheadIsMinusZ() {
        double[] out = new double[3];
        HeadRelativePosition.toListener(0, 0, 5, LOOK_SOUTH, UP, LEFT_WHEN_SOUTH, 1, out);
        assertArrayEquals(new double[] {0, 0, -5}, out, 1e-12);
    }

    @Test
    void theListenersLeftIsMinusX() {
        double[] out = new double[3];
        HeadRelativePosition.toListener(3, 0, 0, LOOK_SOUTH, UP, LEFT_WHEN_SOUTH, 1, out);
        assertArrayEquals(new double[] {-3, 0, 0}, out, 1e-12);
    }

    @Test
    void turningAroundSwapsTheEars() {
        double[] out = new double[3];
        HeadRelativePosition.toListener(3, 0, 0, new double[] {0, 0, -1}, UP, new double[] {-1, 0, 0}, 1, out);
        assertEquals(3, out[0], 1e-12);
    }

    @Test
    void distanceIsKeptAndScalingCentres() {
        double[] out = new double[3];
        double s = Math.sqrt(0.5);
        HeadRelativePosition.toListener(2, 1, -2, new double[] {s, 0, s}, UP, new double[] {s, 0, -s}, 1, out);
        assertEquals(9, out[0] * out[0] + out[1] * out[1] + out[2] * out[2], 1e-12);
        HeadRelativePosition.toListener(2, 1, -2, new double[] {s, 0, s}, UP, new double[] {s, 0, -s}, 0, out);
        assertArrayEquals(new double[] {0, 0, 0}, out, 0);
    }
}
