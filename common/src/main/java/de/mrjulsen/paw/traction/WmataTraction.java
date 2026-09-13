package de.mrjulsen.paw.traction;

import static de.mrjulsen.paw.traction.WmataTractionData.BRAKING_LEVEL;
import static de.mrjulsen.paw.traction.WmataTractionData.COAST_GAIN;
import static de.mrjulsen.paw.traction.WmataTractionData.FREQUENCY;
import static de.mrjulsen.paw.traction.WmataTractionData.NOISE_LEVEL;
import static de.mrjulsen.paw.traction.WmataTractionData.POWERED_LEVEL;
import static de.mrjulsen.paw.traction.WmataTractionData.REFERENCE_HZ;
import static de.mrjulsen.paw.traction.WmataTractionData.TABLE_STEPS;

/**
 * The WMATA 6000-series traction sound as tables over train speed, from standing (0) to top speed (1).
 *
 * Departure stages are laid out over speed ranges. The upper whine has three stages (the starting
 * tone, the plateau and rise, the high section) on two voices, crossfaded at equal power where they
 * overlap; the lower tone rises on its own straight line with speed; the brief upper event sits in its
 * own speed range. Braking has a separate curve on its own voice rather than the departure in
 * reverse.
 *
 * Tonal voices follow traction demand (see TractionDemand): full under power, far quieter while
 * coasting or holding speed, and swapped for the braking curve while braking. The noise bed follows
 * speed alone.
 *
 * Frequencies are features measured in the reference recording; levels and the voice layout were
 * tuned offline and by ear (tools/sound/wmata). None of it is a recovered inverter or motor parameter.
 * Volumes never exceed 1.0.
 */
public final class WmataTraction {

    public enum Voice {
        /** Upper whine stages 1 and 3. */
        STAGE_A,
        /** Upper whine stage 2. */
        STAGE_B,
        /** Narrowband noise on the upper whine's track, once it turns diffuse. */
        UPPER_DIFFUSE,
        /** The braking tone. */
        BRAKE,
        /** The lower tone, rising steadily with speed. */
        RIDGE,
        /** The brief upper event near 2.9 kHz. */
        BRIEF
    }

    private WmataTraction() {}

    /** The frequency baked into the voice's sample. */
    public static double referenceFrequency(Voice voice) {
        return REFERENCE_HZ[voice.ordinal()];
    }

    public static double frequency(Voice voice, double speedFraction) {
        return sample(FREQUENCY[voice.ordinal()], speedFraction);
    }

    /** The voice's level under full traction demand. */
    public static double poweredLevel(Voice voice, double speedFraction) {
        return sample(POWERED_LEVEL[voice.ordinal()], speedFraction);
    }

    /** The voice's level under full braking demand. */
    public static double brakingLevel(Voice voice, double speedFraction) {
        return sample(BRAKING_LEVEL[voice.ordinal()], speedFraction);
    }

    /**
     * @param power traction demand, 0 (coasting) to 1
     * @param brake braking demand, 0 to 1; crossfades from the departure's voices to the braking curve
     */
    public static double volume(Voice voice, double speedFraction, double power, double brake) {
        double p = clamp01(power);
        double b = clamp01(brake);
        double powered = (COAST_GAIN + (1 - COAST_GAIN) * p) * poweredLevel(voice, speedFraction);
        return (1 - b) * powered + b * brakingLevel(voice, speedFraction);
    }

    /** The rolling noise bed, which follows speed alone. */
    public static double noiseVolume(double speedFraction) {
        return sample(NOISE_LEVEL, speedFraction);
    }

    /** Linear interpolation over a table spaced evenly from 0 to 1, clamped at both ends. */
    static double sample(double[] table, double speedFraction) {
        double x = Math.min(1, Math.max(0, speedFraction)) * TABLE_STEPS;
        int i = Math.min((int) Math.floor(x), TABLE_STEPS - 1);
        double f = x - i;
        return table[i] * (1 - f) + table[i + 1] * f;
    }

    private static double clamp01(double value) {
        return Math.min(1, Math.max(0, value));
    }
}
