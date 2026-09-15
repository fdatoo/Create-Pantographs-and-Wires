package de.mrjulsen.paw.traction;

/**
 * A whole-number speed readout as nixie tubes show it: at least two digits ("0 0" at rest, "1 4" at 14),
 * right-aligned across a row of tubes. The shown value only moves once the speed is clearly on another
 * number, so a speed sitting near a half doesn't flicker between two.
 */
public final class SpeedReadout {
    /** How far the speed must be from the shown number before the readout changes, in the readout's unit. */
    static final double HYSTERESIS = 0.6;
    private static final int TWO_DIGITS = 99;

    private int shown;

    /** @param speed speed in any unit; direction doesn't matter. Shown at most 99. */
    public void update(double speed) {
        update(speed, TWO_DIGITS);
    }

    /** @param max the largest number the tubes can show */
    public void update(double speed, int max) {
        double value = Math.abs(speed);
        if (!Double.isFinite(value)) {
            value = 0;
        }
        if (Math.abs(value - shown) >= HYSTERESIS || shown > max) {
            shown = (int) Math.min(max, Math.round(value));
        }
    }

    public int shown() {
        return shown;
    }

    /** The left tube's digit of a two-digit readout. */
    public String tens() {
        return text(2).substring(0, 1);
    }

    /** The right tube's digit of a two-digit readout. */
    public String ones() {
        return text(2).substring(1, 2);
    }

    /** The readout across width characters (two per tube): at least two digits, right-aligned, blanks before. */
    public String text(int width) {
        String digits = shown < 10 ? "0" + shown : String.valueOf(shown);
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return " ".repeat(width - digits.length()) + digits;
    }

    /** The largest number a row of tubes can show. */
    public static int maxFor(int tubes) {
        return tubes >= 5 ? 999_999_999 : (int) Math.pow(10, 2 * Math.max(1, tubes)) - 1;
    }
}
