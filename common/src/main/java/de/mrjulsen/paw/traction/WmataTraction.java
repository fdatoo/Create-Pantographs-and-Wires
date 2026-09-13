package de.mrjulsen.paw.traction;

import static de.mrjulsen.paw.traction.WmataTractionData.*;

/**
 * The WMATA 6000-series traction sound as a function of time along the reference departure:
 * an upper cluster that drops and plateaus then rises and turns diffuse, an independent low
 * sweep, a brief upper event, a later mid ridge, and a rising noise bed.
 *
 * Frequencies are features measured in the recording. Levels, the diffuse upper band and the
 * voice scales were tuned offline against it (tools/sound/wmata). None of it is a recovered
 * inverter or motor parameter. The game maps train speed onto this timeline, which is a design
 * choice rather than something the recording establishes.
 *
 * Volumes are what each voice should play at before the player's traction volume setting,
 * and never exceed 1.0.
 */
public final class WmataTraction {
    private static final double SILENT_DB = -59.999;

    private static final MonotoneCubic UPPER_FREQUENCY = new MonotoneCubic(UPPER_FREQUENCY_KNOTS);
    private static final MonotoneCubic LOW_FREQUENCY = new MonotoneCubic(LOW_FREQUENCY_KNOTS);
    private static final MonotoneCubic BRIEF_FREQUENCY = new MonotoneCubic(BRIEF_FREQUENCY_KNOTS);
    private static final MonotoneCubic MID_FREQUENCY = new MonotoneCubic(MID_FREQUENCY_KNOTS);

    private static final MonotoneCubic UPPER_LEVEL = new MonotoneCubic(UPPER_LEVEL_KNOTS_DB);
    private static final MonotoneCubic UPPER_LINE_CUT = new MonotoneCubic(UPPER_LINE_CUT_KNOTS_DB);
    private static final MonotoneCubic UPPER_DIFFUSE_LEVEL = new MonotoneCubic(UPPER_DIFFUSE_LEVEL_KNOTS_DB);
    private static final MonotoneCubic LOW_LEVEL = new MonotoneCubic(LOW_LEVEL_KNOTS_DB);
    private static final MonotoneCubic BRIEF_LEVEL = new MonotoneCubic(BRIEF_LEVEL_KNOTS_DB);
    private static final MonotoneCubic MID_LEVEL = new MonotoneCubic(MID_LEVEL_KNOTS_DB);
    private static final MonotoneCubic NOISE_LEVEL = new MonotoneCubic(NOISE_LEVEL_KNOTS_DB);

    private WmataTraction() {}

    /** Position along the reference departure for a speed fraction of 0 (standing) to 1 (top). */
    public static double timeForSpeedFraction(double fraction) {
        return Math.min(1, Math.max(0, fraction)) * TIMELINE_SECONDS;
    }

    public static double upperFrequency(double t) {
        return UPPER_FREQUENCY.at(t);
    }

    public static double lowFrequency(double t) {
        return LOW_FREQUENCY.at(t);
    }

    public static double briefFrequency(double t) {
        return BRIEF_FREQUENCY.at(t);
    }

    public static double midFrequency(double t) {
        return MID_FREQUENCY.at(t);
    }

    /** The clean upper line, which gives way to the diffuse band once the cluster broadens. */
    public static double upperLineVolume(double t) {
        return TONAL_VOICE_SCALE * linear(UPPER_LEVEL, t, 0) * linear(UPPER_LINE_CUT, t, 0);
    }

    /** Narrowband noise riding the upper centre, for the stretch where the cluster turns diffuse. */
    public static double upperDiffuseVolume(double t) {
        return TONAL_VOICE_SCALE * linear(UPPER_LEVEL, t, 0) * linear(UPPER_DIFFUSE_LEVEL, t, 0);
    }

    public static double lowVolume(double t) {
        return TONAL_VOICE_SCALE * linear(LOW_LEVEL, t, LOW_GAIN_DB);
    }

    public static double briefVolume(double t) {
        return TONAL_VOICE_SCALE * linear(BRIEF_LEVEL, t, BRIEF_GAIN_DB);
    }

    public static double midVolume(double t) {
        return TONAL_VOICE_SCALE * linear(MID_LEVEL, t, MID_GAIN_DB);
    }

    public static double noiseVolume(double t) {
        return NOISE_VOICE_SCALE * linear(NOISE_LEVEL, t, 0);
    }

    /** A dB level curve as a linear gain, offset by gainDb. -60 dB on the curve means silence. */
    private static double linear(MonotoneCubic curve, double t, double gainDb) {
        double db = curve.at(t);
        return db <= SILENT_DB ? 0 : Math.pow(10, (db + gainDb) / 20);
    }
}
