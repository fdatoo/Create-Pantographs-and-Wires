package de.mrjulsen.paw.traction.pack;

import java.util.Arrays;
import java.util.List;

/**
 * Plays a traction pack for one train: mixes every layer at the pitch and volume its curve gives for
 * the train's speed, gated by its mode's envelope. Mechanical layers named in the settings play in
 * every mode at their curve volume.
 *
 * Every layer keeps its own read head, which keeps moving while the layer is silent, so a layer that
 * fades out and back in continues its loop instead of restarting. Pitch is playback rate, read with
 * cubic interpolation; at pitch 1 the loop's samples come through unchanged. Speed and pitch ramp
 * linearly across each rendered block between the states set on either side of it.
 *
 * setState is called from the game thread and render from the sound thread.
 */
public final class TractionMixer {
    private final List<TractionPack.Layer> layers;
    private final PackSettings settings;
    private final int outputRate;
    private final Envelope power;
    private final Envelope brake;
    private final Envelope coast;
    private final double[] head;
    private final double[] lastPitch;
    private final double[] curve = new double[2];
    private double[] powerGain = new double[0];
    private double[] brakeGain = new double[0];
    private double[] coastGain = new double[0];

    private double targetSpeed;
    private TractionMode targetMode = TractionMode.COAST;
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
        this.lastPitch = new double[layers.size()];
        for (int i = 0; i < layers.size(); i++) {
            lastPitch[i] = layers.get(i).curve().firstPitch();
        }
    }

    /** @param speedMps train speed in metres per second */
    public synchronized void setState(double speedMps, TractionMode mode) {
        targetSpeed = speedMps;
        targetMode = mode;
    }

    public void render(float[] block, int count) {
        double speedTarget;
        TractionMode mode;
        synchronized (this) {
            speedTarget = targetSpeed;
            mode = targetMode;
        }
        if (Double.isNaN(renderedSpeed)) {
            renderedSpeed = speedTarget;
        }
        if (mode != appliedMode) {
            applyMode(mode);
            appliedMode = mode;
        }
        if (powerGain.length < count) {
            powerGain = new double[count];
            brakeGain = new double[count];
            coastGain = new double[count];
        }
        boolean powerAudible = fill(power, powerGain, count);
        boolean brakeAudible = fill(brake, brakeGain, count);
        boolean coastAudible = fill(coast, coastGain, count);

        Arrays.fill(block, 0, count, 0f);
        double speed0 = renderedSpeed;
        double speed1 = speedTarget;
        for (int i = 0; i < layers.size(); i++) {
            TractionPack.Layer layer = layers.get(i);
            float[] source = layer.samples();
            double ratio = layer.sampleRate() / (double) outputRate;

            boolean audible0 = layer.curve().sample(speed0, curve);
            double pitch0 = curve[0];
            double volume0 = audible0 ? curve[1] : 0;
            boolean audible1 = layer.curve().sample(speed1, curve);
            double pitch1 = audible1 ? curve[0] : (audible0 ? pitch0 : lastPitch[i]);
            double volume1 = audible1 ? curve[1] : 0;
            if (!audible0) {
                pitch0 = pitch1;
            }

            double[] gate = null;
            boolean gateAudible = true;
            if (!layer.continuous()) {
                gate = switch (layer.mode()) {
                    case POWER -> powerGain;
                    case BRAKE -> brakeGain;
                    case COAST -> coastGain;
                };
                gateAudible = switch (layer.mode()) {
                    case POWER -> powerAudible;
                    case BRAKE -> brakeAudible;
                    case COAST -> coastAudible;
                };
            }

            if ((volume0 == 0 && volume1 == 0) || !gateAudible) {
                // Silent this block: keep the loop moving so it continues where it would be.
                head[i] = (head[i] + (pitch0 + pitch1) * 0.5 * ratio * count) % source.length;
                lastPitch[i] = pitch1;
                continue;
            }

            double gain = layer.continuous() ? settings.mechanicalGain() : 1.0;
            double h = head[i];
            int length = source.length;
            for (int s = 0; s < count; s++) {
                double u = (s + 1) / (double) count;
                double pitch = pitch0 + (pitch1 - pitch0) * u;
                double volume = (volume0 + (volume1 - volume0) * u) * gain;
                if (gate != null) {
                    volume *= gate[s];
                }
                if (volume != 0) {
                    block[s] += (float) (volume * cubic(source, h));
                }
                h += pitch * ratio;
                if (h >= length) {
                    h -= length;
                }
            }
            head[i] = h;
            lastPitch[i] = pitch1;
        }
        renderedSpeed = speed1;
    }

    private void applyMode(TractionMode mode) {
        PackSettings s = settings;
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
