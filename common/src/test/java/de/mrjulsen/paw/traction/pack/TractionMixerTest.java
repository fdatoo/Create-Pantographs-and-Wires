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
        // After ramping back in, the loop is where it would have been had it never stopped.
        for (int i = BLOCK; i < resumedA.length; i++) {
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
    void mechanicalLayersIgnoreTheMode() {
        float[] loop = sine(96000, 200);
        PackSettings settings = new PackSettings(0.06, PackSettings.Shape.LINEAR, 0.18, PackSettings.Shape.SMOOTHSTEP, 0.35,
            PackSettings.Shape.SMOOTHSTEP, 0.18, PackSettings.Shape.SMOOTHSTEP, Set.of("coast/m"), 1.0);
        TractionPack pack = new TractionPack(List.of(layer("coast/m", TractionMode.COAST, loop, 1.0, 0.7, true)), settings);
        float[] powering = render(new TractionMixer(pack, RATE), 12, TractionMode.POWER, 4);
        float[] braking = render(new TractionMixer(pack, RATE), 12, TractionMode.BRAKE, 4);
        assertArrayEquals(powering, braking, 0f);
    }

    @Test
    void cubicReadIsExactOnWholeSamples() {
        float[] x = {0.1f, -0.4f, 0.9f, 0.3f};
        for (int i = 0; i < x.length; i++) {
            assertEquals(x[i], TractionMixer.cubic(x, i), 1e-7);
        }
    }
}
