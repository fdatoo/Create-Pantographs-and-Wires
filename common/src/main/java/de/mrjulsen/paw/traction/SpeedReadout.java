package de.mrjulsen.paw.traction;

/**
 * A two-digit speed readout in whole metres per second, as a nixie tube shows it: "0 0" at rest, "1 4"
 * at 14 m/s. The shown value only moves once the speed is clearly on another number, so a speed sitting
 * near a half doesn't flicker between two.
 */
public final class SpeedReadout {
    /** How far the speed must be from the shown number before the readout changes. */
    static final double HYSTERESIS_MPS = 0.6;
    private static final int MAX = 99;

    private int shown;

    /** @param speedMps speed in metres per second; direction doesn't matter */
    public void update(double speedMps) {
        double speed = Math.abs(speedMps);
        if (!Double.isFinite(speed)) {
            speed = 0;
        }
        if (Math.abs(speed - shown) >= HYSTERESIS_MPS) {
            shown = (int) Math.min(MAX, Math.round(speed));
        }
    }

    public int shown() {
        return shown;
    }

    /** The left tube's digit. */
    public String tens() {
        return String.valueOf(shown / 10);
    }

    /** The right tube's digit. */
    public String ones() {
        return String.valueOf(shown % 10);
    }
}
