package de.mrjulsen.paw.traction.pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Plays a traction pack for one train: mixes every layer at the pitch and volume its curve gives for
 * the train's speed, gated by its mode's envelope. Mechanical layers named in the settings play in
 * every mode at their curve volume. Ducked layers (a cruising tone) also play in every mode, dipping
 * as the power or brake envelope rises so the whine isn't counted twice.
 *
 * Power and brake gains are each partitioned between their sweep layers and, when a pack has them, their
 * steady-load layers: sweep gets gain times (1 - steady fraction) and steady gets gain times the fraction.
 * The fractions blend toward targets from SteadyLoadDetector with a smoothstep. Ducking uses the whole
 * power and brake gains, so settling into steady load neither unducks the cruising tone nor ducks it twice.
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
    /** An on-demand layer is wanted while the speed is within this of its curve. */
    static final double ON_DEMAND_SPEED_MARGIN_MPS = 1.0;
    /** An on-demand layer's samples are dropped after this long unwanted. */
    static final long ON_DEMAND_IDLE_NANOS = 30_000_000_000L;
    /** Largest change of the mechanical scale per block, so moving the setting mid-ride doesn't click. */
    static final double ROLLING_VOLUME_STEP = 0.02;
    /** Share of the listener's rolling volume left at a standstill, before speed ramps it back up. */
    static final double ROLLING_AT_REST = 0.35;
    /** Speed the rolling ramp reaches full at, for a pack whose mechanical layers name no top speed. */
    static final double ROLLING_RAMP_SPEED_MPS = 40;

    private final List<TractionPack.Layer> layers;
    private final PackSettings settings;
    private final int outputRate;
    private final Envelope power;
    private final Envelope brake;
    private final Envelope coast;
    private final Envelope steadyPower;
    private final Envelope steadyBrake;
    private final boolean hasDucked;
    private final boolean hasPowerSteady;
    private final boolean hasBrakeSteady;
    private final double[] head;
    private final Executor decoder;
    private final LongSupplier clock;
    private final double[] curve = new double[2];
    private double[] powerGain = new double[0];
    private double[] brakeGain = new double[0];
    private double[] coastGain = new double[0];
    private double[] duckGain = new double[0];
    private double[] steadyPowerFraction = new double[0];
    private double[] steadyBrakeFraction = new double[0];
    private double[] powerSweepGain = new double[0];
    private double[] powerSteadyGain = new double[0];
    private double[] brakeSweepGain = new double[0];
    private double[] brakeSteadyGain = new double[0];
    private double[] boundarySpeed = new double[0];
    private double[] pitchAt = new double[0];
    private double[] volumeAt = new double[0];

    private double targetSpeed;
    private TractionMode targetMode = TractionMode.COAST;
    private boolean targetSlopeDriven;
    private double targetSteadyPower;
    private double targetSteadyBrake;
    private double renderedSpeed = Double.NaN;
    private TractionMode appliedMode;
    /** Listener's scale for the mechanical layers, set from the client's setting. */
    private volatile double rollingVolume = 1;
    private double appliedRollingVolume = Double.NaN;
    /** Speed the rolling ramp reaches the listener's full setting at: the top of the mechanical layers' curves. */
    private final double rollingRampSpeed;

    // Diagnostics, written by the sound thread and read by the game thread for logging only.
    private final double[] layerLevel;
    private volatile long blocksRendered;
    private volatile double lastPower;
    private volatile double lastBrake;
    private volatile double lastCoast;
    private volatile double lastDuck = 1;
    private volatile double lastSteadyPower;
    private volatile double lastSteadyBrake;
    private volatile boolean lastModeChangeMoving;
    private volatile double lastModeChangeSeconds;

    /**
     * What the mixer did in its latest block.
     *
     * @param modeChangeMoving  whether the latest mode change used the moving blends rather than departure timings
     * @param modeChangeSeconds how long the envelope for the new mode took to reach full gain
     * @param steadyPower       share of the power gain given to steady-load layers
     * @param steadyBrake       share of the brake gain given to steady-load layers
     * @param loudest           the loudest layers with their effective gains, loudest first
     */
    public record Diagnostics(double speedMps, TractionMode mode, boolean modeChangeMoving, double modeChangeSeconds,
        double power, double brake, double coast, double duck, double steadyPower, double steadyBrake,
        long blocksRendered, List<String> loudest) {}

    public TractionMixer(TractionPack pack, int outputRate) {
        this(pack, outputRate, System::nanoTime);
    }

    /** @param clock nanoseconds, for deciding when an on-demand layer has gone unwanted long enough to drop */
    TractionMixer(TractionPack pack, int outputRate, LongSupplier clock) {
        this.decoder = pack.decoder();
        this.clock = clock;
        this.layers = pack.layers();
        this.settings = pack.settings();
        this.outputRate = outputRate;
        this.power = new Envelope(outputRate);
        this.brake = new Envelope(outputRate);
        this.coast = new Envelope(outputRate);
        this.steadyPower = new Envelope(outputRate);
        this.steadyBrake = new Envelope(outputRate);
        this.head = new double[layers.size()];
        this.hasDucked = layers.stream().anyMatch(TractionPack.Layer::ducked);
        this.hasPowerSteady = layers.stream().anyMatch(l -> l.steady() && l.mode() == TractionMode.POWER);
        this.hasBrakeSteady = layers.stream().anyMatch(l -> l.steady() && l.mode() == TractionMode.BRAKE);
        this.layerLevel = new double[layers.size()];
        this.rollingRampSpeed = layers.stream()
            .filter(l -> l.continuous() && !l.ducked())
            .mapToDouble(l -> l.curve().lastSpeed())
            .max().orElse(ROLLING_RAMP_SPEED_MPS);
    }

    /**
     * How loud the mechanical layers (a pack's rolling, wind and structure noise) play against the rest of
     * the mix, at the top of their speed range. 1 there is the pack's own balance; lower leaves the traction
     * whine standing further out of it. The scale rises with speed toward this setting (see rollingScale), so
     * a standing start is mostly whine and a run at line speed keeps its roar.
     * Set before the first block is rendered it takes effect at once, and afterwards it walks there.
     */
    public void setRollingVolume(double volume) {
        rollingVolume = Math.max(0, volume);
    }

    /**
     * The listener's rolling volume at a given speed. Rolling and wind noise grows with speed, so the
     * setting applies in full only at the top of the mechanical layers' curves and eases off below it,
     * down to ROLLING_AT_REST of it at a standstill. Squared, so the cut holds through the middle of the
     * range rather than fading out as soon as the train moves.
     */
    double rollingScale(double speedMps) {
        if (rollingRampSpeed <= 0) {
            return appliedRollingVolume;
        }
        double u = Math.min(1, Math.max(0, speedMps / rollingRampSpeed));
        return appliedRollingVolume * (ROLLING_AT_REST + (1 - ROLLING_AT_REST) * u * u);
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
        setState(speedMps, mode, slopeDriven, 0, 0);
    }

    /**
     * @param steadyPowerTarget 1 when power has settled into steady load, otherwise 0 (see SteadyLoadDetector)
     * @param steadyBrakeTarget the same for brake
     */
    public synchronized void setState(double speedMps, TractionMode mode, boolean slopeDriven, double steadyPowerTarget, double steadyBrakeTarget) {
        targetSpeed = speedMps;
        targetMode = mode;
        targetSlopeDriven = slopeDriven;
        targetSteadyPower = steadyPowerTarget;
        targetSteadyBrake = steadyBrakeTarget;
    }

    public void render(float[] block, int count) {
        double speedTarget;
        TractionMode mode;
        boolean slopeDriven;
        double steadyPowerTarget;
        double steadyBrakeTarget;
        synchronized (this) {
            speedTarget = targetSpeed;
            mode = targetMode;
            slopeDriven = targetSlopeDriven;
            steadyPowerTarget = targetSteadyPower;
            steadyBrakeTarget = targetSteadyBrake;
        }
        if (Double.isNaN(renderedSpeed)) {
            renderedSpeed = speedTarget;
        }
        double rollingTarget = rollingVolume;
        if (Double.isNaN(appliedRollingVolume)) {
            appliedRollingVolume = rollingTarget;
        } else {
            // Moving the setting mid-ride walks the scale there over a second or so instead of stepping it.
            double step = Math.min(ROLLING_VOLUME_STEP, Math.abs(rollingTarget - appliedRollingVolume));
            appliedRollingVolume += Math.signum(rollingTarget - appliedRollingVolume) * step;
        }
        if (mode != appliedMode) {
            applyMode(mode, slopeDriven);
            appliedMode = mode;
        }
        PackSettings.Steady steady = settings.steady();
        steadyPower.setTarget(hasPowerSteady ? clamp(steadyPowerTarget) : 0, steady.blendSeconds(), PackSettings.Shape.SMOOTHSTEP);
        steadyBrake.setTarget(hasBrakeSteady ? clamp(steadyBrakeTarget) : 0, steady.blendSeconds(), PackSettings.Shape.SMOOTHSTEP);
        if (powerGain.length < count) {
            powerGain = new double[count];
            brakeGain = new double[count];
            coastGain = new double[count];
            duckGain = new double[count];
            steadyPowerFraction = new double[count];
            steadyBrakeFraction = new double[count];
            powerSweepGain = new double[count];
            powerSteadyGain = new double[count];
            brakeSweepGain = new double[count];
            brakeSteadyGain = new double[count];
        }
        fill(power, powerGain, count);
        fill(brake, brakeGain, count);
        boolean coastAudible = fill(coast, coastGain, count);
        fill(steadyPower, steadyPowerFraction, count);
        fill(steadyBrake, steadyBrakeFraction, count);
        boolean powerSweepAudible = false;
        boolean powerSteadyAudible = false;
        boolean brakeSweepAudible = false;
        boolean brakeSteadyAudible = false;
        for (int s = 0; s < count; s++) {
            double sp = clamp(steadyPowerFraction[s]);
            double sb = clamp(steadyBrakeFraction[s]);
            powerSweepGain[s] = powerGain[s] * (1 - sp);
            powerSteadyGain[s] = powerGain[s] * sp;
            brakeSweepGain[s] = brakeGain[s] * (1 - sb);
            brakeSteadyGain[s] = brakeGain[s] * sb;
            powerSweepAudible |= powerSweepGain[s] > 0;
            powerSteadyAudible |= powerSteadyGain[s] > 0;
            brakeSweepAudible |= brakeSweepGain[s] > 0;
            brakeSteadyAudible |= brakeSteadyGain[s] > 0;
        }
        boolean duckAudible = false;
        if (hasDucked) {
            double depth = settings.duckDepth();
            for (int s = 0; s < count; s++) {
                double traction = Math.min(1, Math.max(0, Math.max(powerGain[s], brakeGain[s])));
                duckGain[s] = 1 - depth * traction;
                duckAudible |= duckGain[s] != 0;
            }
        }
        if (count > 0) {
            lastPower = powerGain[count - 1];
            lastBrake = brakeGain[count - 1];
            lastCoast = coastGain[count - 1];
            lastDuck = hasDucked ? duckGain[count - 1] : 1;
            lastSteadyPower = steadyPowerFraction[count - 1];
            lastSteadyBrake = steadyBrakeFraction[count - 1];
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
        long now = clock.getAsLong();
        for (int i = 0; i < layers.size(); i++) {
            TractionPack.Layer layer = layers.get(i);
            LayerAudio audio = layer.audio();
            if (audio.isOnDemand()) {
                // Steady load needs the speed held for over a second first, so a layer wanted once its
                // mode plays near its speed has decoded long before it can be heard.
                boolean wanted = (layer.continuous() || layer.ducked() || layer.mode() == mode)
                    && renderedSpeed >= layer.curve().firstSpeed() - ON_DEMAND_SPEED_MARGIN_MPS
                    && renderedSpeed <= layer.curve().lastSpeed() + ON_DEMAND_SPEED_MARGIN_MPS;
                if (wanted) {
                    audio.want(now, decoder);
                } else {
                    audio.releaseIfIdle(now, ON_DEMAND_IDLE_NANOS);
                }
            }
            float[] source = audio.samples();
            int length = audio.frames();
            double ratio = audio.sampleRate() / (double) outputRate;

            boolean audible = source != null;
            boolean anyVolume = false;
            for (int j = 0; j <= subBlocks; j++) {
                if (layer.curve().sample(boundarySpeed[j], curve)) {
                    pitchAt[j] = curve[0];
                    volumeAt[j] = curve[1];
                    anyVolume |= curve[1] != 0;
                } else {
                    pitchAt[j] = layer.curve().clampedPitch(boundarySpeed[j]);
                    volumeAt[j] = 0;
                }
            }
            audible &= anyVolume;

            double[] gate = null;
            if (layer.ducked()) {
                gate = duckGain;
                audible &= duckAudible;
            } else if (!layer.continuous()) {
                switch (layer.mode()) {
                    case POWER -> {
                        gate = layer.steady() ? powerSteadyGain : powerSweepGain;
                        audible &= layer.steady() ? powerSteadyAudible : powerSweepAudible;
                    }
                    case BRAKE -> {
                        gate = layer.steady() ? brakeSteadyGain : brakeSweepGain;
                        audible &= layer.steady() ? brakeSteadyAudible : brakeSweepAudible;
                    }
                    case COAST -> {
                        gate = coastGain;
                        audible &= coastAudible;
                    }
                }
            }

            double h = head[i];
            if (!audible) {
                // Silent this block: keep the loop moving so it continues where it would be.
                for (int j = 0; j < subBlocks; j++) {
                    int subLength = Math.min(SUB_BLOCK, count - j * SUB_BLOCK);
                    h += (pitchAt[j] + pitchAt[j + 1]) * 0.5 * ratio * subLength;
                }
                head[i] = h % length;
                layerLevel[i] = 0;
                continue;
            }

            boolean mechanical = layer.continuous() && !layer.ducked();
            double base = mechanical ? settings.mechanicalGain() : 1.0;
            int s = 0;
            for (int j = 0; j < subBlocks; j++) {
                int subLength = Math.min(SUB_BLOCK, count - j * SUB_BLOCK);
                double pitch0 = pitchAt[j];
                double pitchStep = (pitchAt[j + 1] - pitch0) / subLength;
                // The rolling scale follows the speed, so it is read at both ends of the sub-block like volume.
                double gain = mechanical ? base * rollingScale(boundarySpeed[j]) : base;
                double gainNext = mechanical ? base * rollingScale(boundarySpeed[j + 1]) : base;
                double volume0 = volumeAt[j] * gain;
                double volumeStep = (volumeAt[j + 1] * gainNext - volume0) / subLength;
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
            double lastGain = mechanical ? base * rollingScale(boundarySpeed[subBlocks]) : base;
            layerLevel[i] = count > 0 ? volumeAt[subBlocks] * lastGain * (gate != null ? gate[count - 1] : 1) : 0;
        }
        blocksRendered++;
    }

    /** A snapshot for logging: envelope gains, the latest mode change, and the loudest layers. */
    public Diagnostics diagnostics(int loudestCount) {
        Integer[] order = new Integer[layers.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        double[] levels = layerLevel.clone();
        Arrays.sort(order, (a, b) -> Double.compare(levels[b], levels[a]));
        List<String> loudest = new ArrayList<>();
        for (int i = 0; i < order.length && loudest.size() < loudestCount; i++) {
            if (levels[order[i]] > 0.001) {
                loudest.add(layers.get(order[i]).name() + " " + String.format("%.2f", levels[order[i]]));
            }
        }
        double speed = renderedSpeed;
        return new Diagnostics(Double.isNaN(speed) ? 0 : speed, appliedMode, lastModeChangeMoving, lastModeChangeSeconds,
            lastPower, lastBrake, lastCoast, lastDuck, lastSteadyPower, lastSteadyBrake, blocksRendered, loudest);
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
            lastModeChangeMoving = true;
            lastModeChangeSeconds = mode == TractionMode.POWER ? powerBlend : s.modeBlendSeconds();
            return;
        }
        lastModeChangeMoving = false;
        lastModeChangeSeconds = switch (mode) {
            case POWER -> s.powerOnSeconds();
            case BRAKE -> s.brakeOnSeconds();
            case COAST -> s.powerOffSeconds();
        };
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

    private static double clamp(double value) {
        return Math.min(1, Math.max(0, value));
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
