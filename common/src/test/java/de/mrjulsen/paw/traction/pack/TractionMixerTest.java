package de.mrjulsen.paw.traction.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TractionMixerTest {
    private static final int RATE = 48000;
    private static final int BLOCK = 2400;

    /** A loop holding exactly `cycles` cycles of a sine at amplitude 1. */
    private static float[] sine(int length, int cycles) {
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            out[i] = (float) Math.sin(2 * Math.PI * cycles * i / length);
        }
        return out;
    }

    private static TractionPack.Layer layer(String name, TractionMode mode, float[] samples, double pitch, double volume, boolean continuous) {
        LayerCurve curve = new LayerCurve(List.of(new double[] {0, pitch, volume}, new double[] {40, pitch, volume}));
        return new TractionPack.Layer(name, mode, curve, samples, RATE, continuous);
    }

    private static float[] render(TractionMixer mixer, double speed, TractionMode mode, int blocks) {
        float[] all = new float[blocks * BLOCK];
        float[] block = new float[BLOCK];
        for (int b = 0; b < blocks; b++) {
            mixer.setState(speed, mode);
            mixer.render(block, BLOCK);
            System.arraycopy(block, 0, all, b * BLOCK, BLOCK);
        }
        return all;
    }

    private static double rms(float[] x, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += x[i] * (double) x[i];
        }
        return Math.sqrt(sum / (to - from));
    }

    private static int zeroCrossings(float[] x, int from, int to) {
        int crossings = 0;
        for (int i = from + 1; i < to; i++) {
            if ((x[i - 1] < 0) != (x[i] < 0)) {
                crossings++;
            }
        }
        return crossings;
    }

    @Test
    void pitchOnePlaysTheLoopSamplesUnchanged() {
        float[] loop = sine(96000, 480);
        TractionPack pack = new TractionPack(List.of(layer("coast/m", TractionMode.COAST, loop, 1.0, 0.5, true)), PackSettings.defaults());
        float[] out = render(new TractionMixer(pack, RATE), 10, TractionMode.POWER, 4);
        for (int i = 0; i < out.length; i++) {
            assertEquals(0.5f * loop[i % loop.length], out[i], 1e-6f, "sample " + i);
        }
    }

    @Test
    void pitchTwoDoublesTheFrequency() {
        float[] loop = sine(96000, 240);  // 120 Hz at pitch 1
        TractionPack pack = new TractionPack(List.of(layer("coast/m", TractionMode.COAST, loop, 2.0, 1.0, true)), PackSettings.defaults());
        float[] out = render(new TractionMixer(pack, RATE), 10, TractionMode.COAST, 20);
        // One second at 240 Hz: 480 zero crossings.
        assertEquals(480, zeroCrossings(out, 0, RATE), 2);
    }

    @Test
    void silentOutsideItsCurveButTheLoopKeepsMoving() {
        float[] loop = sine(96000, 331);
        LayerCurve curve = new LayerCurve(List.of(new double[] {10, 1.0, 1.0}, new double[] {20, 1.0, 1.0}));
        TractionPack.Layer gappy = new TractionPack.Layer("coast/g", TractionMode.COAST, curve, loop, RATE, true);
        TractionPack.Layer steady = layer("coast/s", TractionMode.COAST, loop, 1.0, 1.0, true);

        TractionMixer interrupted = new TractionMixer(new TractionPack(List.of(gappy), PackSettings.defaults()), RATE);
        TractionMixer continuous = new TractionMixer(new TractionPack(List.of(steady), PackSettings.defaults()), RATE);
        float[] before = render(interrupted, 15, TractionMode.COAST, 3);
        render(continuous, 15, TractionMode.COAST, 3);
        float[] gap = render(interrupted, 30, TractionMode.COAST, 5);
        render(continuous, 15, TractionMode.COAST, 5);
        assertEquals(0, rms(gap, 1 * BLOCK, gap.length), 0, "silent while outside the curve");
        float[] resumedA = render(interrupted, 15, TractionMode.COAST, 3);
        float[] resumedB = render(continuous, 15, TractionMode.COAST, 3);
        assertTrue(rms(before, 0, before.length) > 0.5);
        // After gliding back into the curve, the loop is where it would have been had it never stopped.
        for (int i = 2 * BLOCK; i < resumedA.length; i++) {
            assertEquals(resumedB[i], resumedA[i], 1e-5f, "sample " + i);
        }
    }

    @Test
    void powerOnsetFollowsItsEnvelope() {
        float[] dc = new float[48000];
        java.util.Arrays.fill(dc, 1f);
        TractionPack pack = new TractionPack(List.of(layer("power/t", TractionMode.POWER, dc, 1.0, 1.0, false)), PackSettings.defaults());
        TractionMixer mixer = new TractionMixer(pack, RATE);
        render(mixer, 10, TractionMode.COAST, 2);
        float[] out = render(mixer, 10, TractionMode.POWER, 2);
        assertEquals(1440 / 2880.0, out[1439], 1e-6, "halfway through the 60 ms linear onset");
        assertEquals(1.0, out[2879], 1e-6, "fully on after 60 ms");
        assertEquals(1.0, out[out.length - 1], 1e-6);
    }

    @Test
    void brakeOnsetIsSmoothstepAndInterruptionsStartFromTheCurrentGain() {
        float[] dc = new float[48000];
        java.util.Arrays.fill(dc, 1f);
        TractionPack pack = new TractionPack(List.of(
            layer("brake/t", TractionMode.BRAKE, dc, 1.0, 1.0, false),
            layer("power/t", TractionMode.POWER, dc, 1.0, 0.0, false)
        ), PackSettings.defaults());
        TractionMixer mixer = new TractionMixer(pack, RATE);
        float[] out = render(mixer, 10, TractionMode.BRAKE, 8);  // 350 ms onset = 16800 samples
        double quarter = 0.25 * 0.25 * (3 - 2 * 0.25);
        assertEquals(quarter, out[4199], 1e-6);
        assertEquals(0.5, out[8399], 1e-6);
        assertEquals(1.0, out[16799], 1e-6);

        TractionMixer reversed = new TractionMixer(pack, RATE);
        float[] partial = render(reversed, 10, TractionMode.BRAKE, 1);  // 50 ms into the brake onset
        float lastGain = partial[BLOCK - 1];
        float[] released = render(reversed, 10, TractionMode.POWER, 1);
        assertTrue(Math.abs(released[0] - lastGain) < 1e-3, "release starts from " + lastGain + " not " + released[0]);
        assertTrue(released[BLOCK - 1] < lastGain);
    }

    @Test
    void speedStepsGlideInsteadOfJumping() {
        float[] dc = new float[48000];
        java.util.Arrays.fill(dc, 1f);
        // Volume equals speed / 40, so the output traces the speed the mixer is using.
        LayerCurve curve = new LayerCurve(List.of(new double[] {0, 1.0, 0.0}, new double[] {40, 1.0, 1.0}));
        TractionPack pack = new TractionPack(List.of(new TractionPack.Layer("coast/m", TractionMode.COAST, curve, dc, RATE, true)), PackSettings.defaults());
        TractionMixer mixer = new TractionMixer(pack, RATE);
        render(mixer, 10, TractionMode.COAST, 2);
        float[] out = render(mixer, 20, TractionMode.COAST, 8);
        double largestStep = 0;
        double previous = 10 / 40.0;
        for (float sample : out) {
            largestStep = Math.max(largestStep, Math.abs(sample - previous));
            previous = sample;
        }
        // A 10 m/s jump spread over a 50 ms glide: no sample moves more than 0.2 % of full scale.
        assertTrue(largestStep < 0.002, "largest step " + largestStep);
        assertEquals(0.5, out[out.length - 1], 1e-3, "settled at the new speed within 400 ms");
    }

    @Test
    void mechanicalLayersIgnoreTheMode() {
        float[] loop = sine(96000, 200);
        PackSettings settings = new PackSettings(0.06, PackSettings.Shape.LINEAR, 0.18, PackSettings.Shape.SMOOTHSTEP, 0.35,
            PackSettings.Shape.SMOOTHSTEP, 0.18, PackSettings.Shape.SMOOTHSTEP, Set.of("coast/m"), 1.0,
            Set.of(), 0.85, 0, 0, PackSettings.Shape.SMOOTHSTEP, 1.0, 0);
        TractionPack pack = new TractionPack(List.of(layer("coast/m", TractionMode.COAST, loop, 1.0, 0.7, true)), settings);
        float[] powering = render(new TractionMixer(pack, RATE), 12, TractionMode.POWER, 4);
        float[] braking = render(new TractionMixer(pack, RATE), 12, TractionMode.BRAKE, 4);
        assertArrayEquals(powering, braking, 0f);
    }

    private static float[] ones() {
        float[] dc = new float[48000];
        java.util.Arrays.fill(dc, 1f);
        return dc;
    }

    private static PackSettings cruisingSettings(double blendSeconds) {
        return cruisingSettings(blendSeconds, 0);
    }

    private static PackSettings cruisingSettings(double blendSeconds, double slopePowerBlendSeconds) {
        return new PackSettings(0.06, PackSettings.Shape.LINEAR, 0.18, PackSettings.Shape.SMOOTHSTEP, 0.35,
            PackSettings.Shape.SMOOTHSTEP, 0.18, PackSettings.Shape.SMOOTHSTEP, Set.of(), 1.0,
            Set.of("coast/cruising"), 0.85, 0.2, blendSeconds, PackSettings.Shape.SMOOTHSTEP, 1.0, slopePowerBlendSeconds);
    }

    @Test
    void powerBroughtInByAClimbSwellsOverItsOwnBlend() {
        float[] dc = ones();
        TractionPack pack = new TractionPack(List.of(layer("power/t", TractionMode.POWER, dc, 1.0, 1.0, false)), cruisingSettings(0.25, 1.0));
        TractionMixer mixer = new TractionMixer(pack, RATE);
        render(mixer, 10, TractionMode.COAST, 2);
        float[] out = new float[21 * BLOCK];
        float[] block = new float[BLOCK];
        for (int b = 0; b < 21; b++) {
            mixer.setState(10, TractionMode.POWER, true);
            mixer.render(block, BLOCK);
            System.arraycopy(block, 0, out, b * BLOCK, BLOCK);
        }
        assertEquals(0.5, out[23999], 1e-6, "smoothstep midpoint of the 1 s climb swell");
        assertEquals(1.0, out[47999], 1e-6);
    }

    @Test
    void cruisingDucksUnderPowerAndBrakeAndReturnsWhenCoasting() {
        float[] dc = ones();
        TractionPack pack = new TractionPack(List.of(
            new TractionPack.Layer("coast/cruising", TractionMode.COAST, new LayerCurve(List.of(new double[] {0, 1.0, 0.2}, new double[] {40, 1.0, 0.2})), dc, RATE, false, true),
            layer("power/t", TractionMode.POWER, dc, 1.0, 0.0, false),
            layer("brake/t", TractionMode.BRAKE, dc, 1.0, 0.0, false)
        ), cruisingSettings(0));
        TractionMixer mixer = new TractionMixer(pack, RATE);
        float[] coasting = render(mixer, 14, TractionMode.COAST, 2);
        assertEquals(0.2, coasting[coasting.length - 1], 1e-6, "full CSV volume at neutral demand");
        float[] powering = render(mixer, 14, TractionMode.POWER, 4);
        assertEquals(0.2 * 0.15, powering[powering.length - 1], 1e-6, "a faint 15 % remains under full power");
        float[] braking = render(mixer, 14, TractionMode.BRAKE, 10);
        double lowest = 1;
        for (float sample : braking) lowest = Math.min(lowest, sample);
        assertTrue(lowest > 0.2 * 0.15 - 1e-6, "never dips below the floor while handing over from power to brake");
        assertEquals(0.2 * 0.15, braking[braking.length - 1], 1e-6);
        float[] coastingAgain = render(mixer, 14, TractionMode.COAST, 10);
        assertEquals(0.2, coastingAgain[coastingAgain.length - 1], 1e-6);
    }

    @Test
    void modeChangesBlendWhileMovingButDeparturesKeepTheirOnset() {
        float[] dc = ones();
        TractionPack pack = new TractionPack(List.of(layer("power/t", TractionMode.POWER, dc, 1.0, 1.0, false)), cruisingSettings(0.25));

        TractionMixer moving = new TractionMixer(pack, RATE);
        render(moving, 10, TractionMode.COAST, 2);
        float[] blend = render(moving, 10, TractionMode.POWER, 6);   // 250 ms = 12000 samples
        assertEquals(0.5, blend[5999], 1e-6, "smoothstep midpoint of the 250 ms blend");
        assertEquals(1.0, blend[11999], 1e-6);

        TractionMixer departing = new TractionMixer(pack, RATE);
        render(departing, 0.5, TractionMode.COAST, 2);
        float[] onset = render(departing, 0.5, TractionMode.POWER, 2);
        assertEquals(0.5, onset[1439], 1e-6, "accepted 60 ms linear onset from rest");
        assertEquals(1.0, onset[2879], 1e-6);
    }

    @Test
    void diagnosticsReportWhatWasMixed() {
        float[] dc = ones();
        TractionPack pack = new TractionPack(List.of(
            layer("power/t", TractionMode.POWER, dc, 1.0, 0.6, false),
            layer("brake/t", TractionMode.BRAKE, dc, 1.0, 0.9, false),
            new TractionPack.Layer("coast/cruising", TractionMode.COAST, new LayerCurve(List.of(new double[] {0, 1.0, 0.2}, new double[] {40, 1.0, 0.2})), dc, RATE, false, true)
        ), cruisingSettings(0.25, 1.0));
        TractionMixer mixer = new TractionMixer(pack, RATE);
        render(mixer, 10, TractionMode.COAST, 2);
        float[] block = new float[BLOCK];
        for (int b = 0; b < 30; b++) {
            mixer.setState(10, TractionMode.POWER, true);
            mixer.render(block, BLOCK);
        }
        TractionMixer.Diagnostics d = mixer.diagnostics(3);
        assertEquals(TractionMode.POWER, d.mode());
        assertTrue(d.modeChangeMoving());
        assertEquals(1.0, d.modeChangeSeconds(), 0, "the climb swell was used");
        assertEquals(1.0, d.power(), 1e-9);
        assertEquals(0.0, d.brake(), 1e-9);
        assertEquals(0.15, d.duck(), 1e-9);
        assertEquals(32, d.blocksRendered());
        assertEquals(List.of("power/t 0.60", "coast/cruising 0.03"), d.loudest(), "silent brake layer left out");
    }

    @Test
    void cubicReadIsExactOnWholeSamples() {
        float[] x = {0.1f, -0.4f, 0.9f, 0.3f};
        for (int i = 0; i < x.length; i++) {
            assertEquals(x[i], TractionMixer.cubic(x, i), 1e-7);
        }
    }
}
