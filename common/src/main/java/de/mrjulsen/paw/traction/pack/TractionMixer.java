package de.mrjulsen.paw.traction.pack;

import java.util.Arrays;
import java.util.List;

/**
 * Plays a traction pack for one train: mixes every layer at the pitch and volume its curve gives for
 * the train's speed, gated by its mode's envelope. Mechanical layers named in the settings play in
 * every mode at their curve volume. Ducked layers (a cruising tone) also play in every mode, dipping
 * as the power or brake envelope rises so the whine isn't counted twice.
 *
 * Every layer keeps its own read head, which keeps moving while the layer is silent, so a layer that
 * fades out and back in continues its loop instead of restarting. Pitch is playback rate, read with
 * cubic interpolation; at pitch 1 the loop's samples come through unchanged.
 *
 * Speed arrives once per game tick. The mixer glides toward each new value (a one-pole with a 50 ms
 * time constant, evaluated every 5 ms) so a curve's pitch never steps from tick to tick.
 *
 * setState is called from the game thread and render from the sound thread.
 */
public final class TractionMixer {
    /** Samples between curve evaluations; pitch and volume ramp linearly in between. */
    static final int SUB_BLOCK = 240;
    /** Time constant of the glide toward each new speed. */
    static final double SPEED_GLIDE_SECONDS = 0.05;

    private final List<TractionPack.Layer> layers;
    private final PackSettings settings;
    private final int outputRate;
    private final Envelope power;
    private final Envelope brake;
    private final Envelope coast;
    private final double[] head;
    private final double[] curve = new double[2];
    private double[] powerGain = new double[0];
    private double[] brakeGain = new double[0];
    private double[] coastGain = new double[0];
    private double[] duckGain = new double[0];
    private final boolean hasDucked;
    private double[] boundarySpeed = new double[0];
    private double[] pitchAt = new double[0];
    private double[] volumeAt = new double[0];

    private double targetSpeed;
    private TractionMode targetMode = TractionMode.COAST;
    private boolean targetSlopeDriven;
    private double renderedSpeed = Double.NaN;
    private TractionMode appliedMode;

    public TractionMixer(TractionPack pack, int outputRate) {
        this.layers = pack.layers();
        this.settings = pack.settings();
        this.outputRate = outputRate;
        this.power = new Envelope(outputRate);
        this.brake = new Envelope(outputRate);
        this.coast = new Envelope(outputRate);
        this.head = new double[layers.size()];
        this.hasDucked = layers.stream().anyMatch(TractionPack.Layer::ducked);
    }

    /** @param speedMps train speed in metres per second */
    public synchronized void setState(double speedMps, TractionMode mode) {
        setState(speedMps, mode, false);
    }

    /**
     * @param speedMps    train speed in metres per second
     * @param slopeDriven whether a climb or descent, rather than the train speeding up or slowing down,
     *                    brought in the mode; power from a climb swells in over its own, longer blend
     */
    public synchronized void setState(double speedMps, TractionMode mode, boolean slopeDriven) {
        targetSpeed = speedMps;
        targetMode = mode;
        targetSlopeDriven = slopeDriven;
    }

    public void render(float[] block, int count) {
        double speedTarget;
        TractionMode mode;
        boolean slopeDriven;
        synchronized (this) {
            speedTarget = targetSpeed;
            mode = targetMode;
            slopeDriven = targetSlopeDriven;
        }
        if (Double.isNaN(renderedSpeed)) {
            renderedSpeed = speedTarget;
        }
        if (mode != appliedMode) {
            applyMode(mode, slopeDriven);
            appliedMode = mode;
        }
        if (powerGain.length < count) {
            powerGain = new double[count];
            brakeGain = new double[count];
            coastGain = new double[count];
            duckGain = new double[count];
        }
        boolean powerAudible = fill(power, powerGain, count);
        boolean brakeAudible = fill(brake, brakeGain, count);
        boolean coastAudible = fill(coast, coastGain, count);
        boolean duckAudible = false;
        if (hasDucked) {
            double depth = settings.duckDepth();
            for (int s = 0; s < count; s++) {
                double traction = Math.min(1, Math.max(0, Math.max(powerGain[s], brakeGain[s])));
                duckGain[s] = 1 - depth * traction;
                duckAudible |= duckGain[s] != 0;
            }
        }

        int subBlocks = (count + SUB_BLOCK - 1) / SUB_BLOCK;
        if (boundarySpeed.length < subBlocks + 1) {
            boundarySpeed = new double[subBlocks + 1];
            pitchAt = new double[subBlocks + 1];
            volumeAt = new double[subBlocks + 1];
        }
        boundarySpeed[0] = renderedSpeed;
        for (int j = 1; j <= subBlocks; j++) {
            int length = Math.min(SUB_BLOCK, count - (j - 1) * SUB_BLOCK);
            double follow = 1 - Math.exp(-length / (SPEED_GLIDE_SECONDS * outputRate));
            renderedSpeed += (speedTarget - renderedSpeed) * follow;
            boundarySpeed[j] = renderedSpeed;
        }

        Arrays.fill(block, 0, count, 0f);
        for (int i = 0; i < layers.size(); i++) {
            TractionPack.Layer layer = layers.get(i);
            float[] source = layer.samples();
            int length = source.length;
            double ratio = layer.sampleRate() / (double) outputRate;

            boolean audible = false;
            for (int j = 0; j <= subBlocks; j++) {
                if (layer.curve().sample(boundarySpeed[j], curve)) {
                    pitchAt[j] = curve[0];
                    volumeAt[j] = curve[1];
                    audible |= curve[1] != 0;
                } else {
                    pitchAt[j] = layer.curve().clampedPitch(boundarySpeed[j]);
                    volumeAt[j] = 0;
                }
            }

            double[] gate = null;
            if (layer.ducked()) {
                gate = duckGain;
                audible &= duckAudible;
            } else if (!layer.continuous()) {
                gate = switch (layer.mode()) {
                    case POWER -> powerGain;
                    case BRAKE -> brakeGain;
                    case COAST -> coastGain;
                };
                audible &= switch (layer.mode()) {
                    case POWER -> powerAudible;
                    case BRAKE -> brakeAudible;
                    case COAST -> coastAudible;
                };
            }

            double h = head[i];
            if (!audible) {
                // Silent this block: keep the loop moving so it continues where it would be.
                for (int j = 0; j < subBlocks; j++) {
                    int subLength = Math.min(SUB_BLOCK, count - j * SUB_BLOCK);
                    h += (pitchAt[j] + pitchAt[j + 1]) * 0.5 * ratio * subLength;
                }
                head[i] = h % length;
                continue;
            }

            double gain = layer.continuous() && !layer.ducked() ? settings.mechanicalGain() : 1.0;
            int s = 0;
            for (int j = 0; j < subBlocks; j++) {
                int subLength = Math.min(SUB_BLOCK, count - j * SUB_BLOCK);
                double pitch0 = pitchAt[j];
                double pitchStep = (pitchAt[j + 1] - pitch0) / subLength;
                double volume0 = volumeAt[j] * gain;
                double volumeStep = (volumeAt[j + 1] * gain - volume0) / subLength;
                for (int k = 1; k <= subLength; k++, s++) {
                    double volume = volume0 + volumeStep * k;
                    if (gate != null) {
                        volume *= gate[s];
                    }
                    if (volume != 0) {
                        block[s] += (float) (volume * cubic(source, h));
                    }
                    h += (pitch0 + pitchStep * k) * ratio;
                    if (h >= length) {
                        h -= length;
                    }
                }
            }
            head[i] = h;
        }
    }

    private void applyMode(TractionMode mode, boolean slopeDriven) {
        PackSettings s = settings;
        if (s.modeBlendSeconds() > 0 && renderedSpeed >= s.departureSpeedMps()) {
            // Moving: every envelope blends from its current gain over the same time, except power
            // brought in by a climb, which swells in like a driver notching up.
            double powerBlend = mode == TractionMode.POWER && slopeDriven && s.slopePowerBlendSeconds() > 0
                ? s.slopePowerBlendSeconds()
                : s.modeBlendSeconds();
            power.setTarget(mode == TractionMode.POWER ? 1 : 0, powerBlend, s.modeBlendShape());
            brake.setTarget(mode == TractionMode.BRAKE ? 1 : 0, s.modeBlendSeconds(), s.modeBlendShape());
            coast.setTarget(mode == TractionMode.COAST ? 1 : 0, s.modeBlendSeconds(), s.modeBlendShape());
            return;
        }
        switch (mode) {
            case POWER -> {
                power.setTarget(1, s.powerOnSeconds(), s.powerOnShape());
                brake.setTarget(0, s.brakeOffSeconds(), s.brakeOffShape());
                coast.setTarget(0, s.powerOffSeconds(), s.powerOffShape());
            }
            case BRAKE -> {
                brake.setTarget(1, s.brakeOnSeconds(), s.brakeOnShape());
                power.setTarget(0, s.powerOffSeconds(), s.powerOffShape());
                coast.setTarget(0, s.powerOffSeconds(), s.powerOffShape());
            }
            case COAST -> {
                power.setTarget(0, s.powerOffSeconds(), s.powerOffShape());
                brake.setTarget(0, s.brakeOffSeconds(), s.brakeOffShape());
                coast.setTarget(1, s.powerOnSeconds(), s.powerOnShape());
            }
        }
    }

    /** Fills a block of envelope gains; returns whether any of them is above zero. */
    private static boolean fill(Envelope envelope, double[] gains, int count) {
        boolean audible = envelope.gain() > 0;
        for (int s = 0; s < count; s++) {
            gains[s] = envelope.next();
            audible |= gains[s] > 0;
        }
        return audible;
    }

    /** Four-point cubic (Catmull-Rom) read of a looping buffer; exact at whole-sample positions. */
    static double cubic(float[] x, double position) {
        int n = x.length;
        int i = (int) position;
        double t = position - i;
        double xm1 = x[(i - 1 + n) % n];
        double x0 = x[i % n];
        double x1 = x[(i + 1) % n];
        double x2 = x[(i + 2) % n];
        double c1 = 0.5 * (x1 - xm1);
        double c2 = xm1 - 2.5 * x0 + 2 * x1 - 0.5 * x2;
        double c3 = 0.5 * (x2 - xm1) + 1.5 * (x0 - x1);
        return ((c3 * t + c2) * t + c1) * t + x0;
    }
}
