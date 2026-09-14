package de.mrjulsen.paw.client.sound.synth;

import de.mrjulsen.paw.traction.OnixTraction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Renders one train's traction sound from OnixTraction's lines: a bank of phase-continuous oscillators
 * whose targets the client thread updates once a tick while Minecraft's sound thread renders blocks.
 * Amplitudes ramp across each block; a frequency moving less than GLIDE_LIMIT glides across it, and a
 * larger step (a carrier change) lands at once, as the real inverter switches.
 */
@Environment(EnvType.CLIENT)
public final class OnixTractionSynth implements SynthAudioStream.Source {
    private static final double GLIDE_LIMIT = 0.08;
    private static final int TABLE_SIZE = 4096;
    private static final float[] SINE = new float[TABLE_SIZE + 1];

    static {
        for (int i = 0; i <= TABLE_SIZE; i++) {
            SINE[i] = (float) Math.sin(2 * Math.PI * i / TABLE_SIZE);
        }
    }

    private final double[] stateFrequency = new double[OnixTraction.LINE_COUNT];
    private final double[] stateAmplitude = new double[OnixTraction.LINE_COUNT];
    private final double[] targetFrequency = new double[OnixTraction.LINE_COUNT];
    private final double[] targetAmplitude = new double[OnixTraction.LINE_COUNT];

    // Owned by the sound thread.
    private final double[] frequency = new double[OnixTraction.LINE_COUNT];
    private final double[] amplitude = new double[OnixTraction.LINE_COUNT];
    private final double[] phase = new double[OnixTraction.LINE_COUNT];
    private final double[] nextFrequency = new double[OnixTraction.LINE_COUNT];
    private final double[] nextAmplitude = new double[OnixTraction.LINE_COUNT];

    /** Client thread: the train's state this tick. */
    public void setState(double speed, double power, double brake) {
        OnixTraction.lines(speed, power, brake, stateFrequency, stateAmplitude);
        synchronized (this) {
            System.arraycopy(stateFrequency, 0, targetFrequency, 0, OnixTraction.LINE_COUNT);
            System.arraycopy(stateAmplitude, 0, targetAmplitude, 0, OnixTraction.LINE_COUNT);
        }
    }

    /** Sound thread: the next block of samples. */
    @Override
    public void render(float[] block, int count) {
        synchronized (this) {
            System.arraycopy(targetFrequency, 0, nextFrequency, 0, OnixTraction.LINE_COUNT);
            System.arraycopy(targetAmplitude, 0, nextAmplitude, 0, OnixTraction.LINE_COUNT);
        }
        java.util.Arrays.fill(block, 0, count, 0f);
        for (int line = 0; line < OnixTraction.LINE_COUNT; line++) {
            double f0 = frequency[line];
            double f1 = nextFrequency[line];
            double a0 = amplitude[line];
            double a1 = nextAmplitude[line];
            frequency[line] = f1;
            amplitude[line] = a1;
            if (a0 == 0 && a1 == 0) {
                continue;
            }
            boolean jump = f0 <= 0 || Math.abs(f1 - f0) > GLIDE_LIMIT * Math.max(f0, 1);
            double p = phase[line];
            for (int s = 0; s < count; s++) {
                double u = (s + 1) / (double) count;
                double f = jump ? f1 : f0 + (f1 - f0) * u;
                p += f / SynthAudioStream.SAMPLE_RATE;
                if (p >= 1) {
                    p -= Math.floor(p);
                }
                block[s] += (float) ((a0 + (a1 - a0) * u) * sine(p));
            }
            phase[line] = p;
        }
    }

    private static float sine(double phase) {
        double position = phase * TABLE_SIZE;
        int index = (int) position;
        float fraction = (float) (position - index);
        return SINE[index] + (SINE[index + 1] - SINE[index]) * fraction;
    }
}
