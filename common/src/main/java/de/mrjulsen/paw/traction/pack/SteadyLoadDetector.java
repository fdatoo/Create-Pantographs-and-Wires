package de.mrjulsen.paw.traction.pack;

/**
 * Decides when powering or braking has settled into a steady load: the train holds its speed while the
 * motors keep working, as on a long grade at a speed limit. A pack's steady-load layers then take over
 * from its acceleration and braking layers, which were made as moments of a sweep and drone when held.
 *
 * Entry follows the steady-load addon's rules (PackSettings.Steady). Each family, power and brake,
 * qualifies separately: it must be the heard mode, the speed in range, the actual acceleration small and
 * the speed within a narrow span since qualifying began, continuously for the hold time. Coasting
 * beforehand doesn't count.
 *
 * Exit is looser than the addon first specified. Create's speed cap on curves lifts for a few ticks at
 * track joins, stepping the speed up a quarter of a metre per second and back, which ended steady load
 * and cost another hold and blend to return. So a steady family ends only when the speed has moved the
 * exit deviation away from the held speed and the net acceleration over the exit window still clears the
 * exit threshold. A slow drift past the deviation moves the held speed instead. Losing the family or the
 * speed range still ends it at once.
 *
 * Speeds here are actual speed, not the gravity-compensated effort the mode detector uses. Call once
 * per game tick.
 */
public final class SteadyLoadDetector {
    private static final double TICKS_PER_SECOND = 20;

    private PackSettings.Steady settings = PackSettings.Steady.defaults();
    private int holdTicks;
    private int exitTicks;
    private double[] recent = new double[1];
    private int recentCount;
    private int recentNext;
    private double previousSpeed = Double.NaN;
    private final Family power = new Family();
    private final Family brake = new Family();

    public SteadyLoadDetector() {
        configure(PackSettings.Steady.defaults());
    }

    public void configure(PackSettings.Steady settings) {
        if (settings.equals(this.settings) && recent.length > 1) {
            return;
        }
        this.settings = settings;
        this.holdTicks = Math.max(1, (int) Math.round(settings.holdSeconds() * TICKS_PER_SECOND));
        this.exitTicks = Math.max(1, (int) Math.round(settings.exitHoldSeconds() * TICKS_PER_SECOND));
        this.recent = new double[exitTicks + 1];
        this.recentCount = 0;
        this.recentNext = 0;
    }

    /**
     * @param speedMps train speed in metres per second
     * @param heard    the mode the voice plays, after contact and every other gate
     */
    public void update(double speedMps, TractionMode heard) {
        double speed = Math.abs(speedMps);
        double acceleration = Double.isNaN(previousSpeed) ? 0 : Math.abs(speed - previousSpeed) * TICKS_PER_SECOND;
        previousSpeed = speed;

        recent[recentNext] = speed;
        recentNext = (recentNext + 1) % recent.length;
        recentCount = Math.min(recentCount + 1, recent.length);
        double oldest = recent[(recentNext - recentCount + recent.length) % recent.length];
        double windowAcceleration = recentCount > 1
            ? Math.abs(speed - oldest) * TICKS_PER_SECOND / (recentCount - 1)
            : 0;

        boolean inRange = speed >= settings.minSpeedMps() && speed <= settings.maxSpeedMps();
        power.update(heard == TractionMode.POWER && inRange, speed, acceleration, windowAcceleration);
        brake.update(heard == TractionMode.BRAKE && inRange, speed, acceleration, windowAcceleration);
    }

    public boolean powerSteady() {
        return power.steady;
    }

    public boolean brakeSteady() {
        return brake.steady;
    }

    /** 1 while power is in steady load, otherwise 0; the mixer blends toward it. */
    public double powerTarget() {
        return power.steady ? 1 : 0;
    }

    public double brakeTarget() {
        return brake.steady ? 1 : 0;
    }

    private final class Family {
        private boolean steady;
        private int qualifying;
        private double lowest;
        private double highest;
        private double held;

        private void update(boolean eligible, double speed, double acceleration, double windowAcceleration) {
            if (!eligible) {
                steady = false;
                qualifying = 0;
                return;
            }
            if (steady) {
                if (Math.abs(speed - held) >= settings.exitSpeedDeviationMps()) {
                    if (windowAcceleration >= settings.exitAbsAccelerationMps2()) {
                        steady = false;
                        qualifying = 0;
                    } else {
                        // Drifted this far without a clear change of speed: hold here instead.
                        held = speed;
                    }
                }
                return;
            }
            if (acceleration > settings.enterAbsAccelerationMps2()) {
                qualifying = 0;
                return;
            }
            if (qualifying == 0) {
                lowest = speed;
                highest = speed;
            } else {
                lowest = Math.min(lowest, speed);
                highest = Math.max(highest, speed);
            }
            if (highest - lowest > settings.speedSpanMps()) {
                // Drifting: start the hold again from here.
                qualifying = 0;
                lowest = speed;
                highest = speed;
            }
            if (++qualifying >= holdTicks) {
                steady = true;
                held = (lowest + highest) / 2;
            }
        }
    }
}
