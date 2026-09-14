package de.mrjulsen.paw.traction.pack;

/**
 * Decides when powering or braking has settled into a steady load: the train holds its speed while the
 * motors keep working, as on a long grade at a speed limit. A pack's steady-load layers then take over
 * from its acceleration and braking layers, which were made as moments of a sweep and drone when held.
 *
 * Follows the steady-load addon's rules (PackSettings.Steady). Each family, power and brake, qualifies
 * separately: it must be the heard mode, the speed in range, the actual acceleration small and the speed
 * within a narrow span since qualifying began, continuously for the hold time. Coasting beforehand doesn't
 * count. Once steady, a clear change of speed held for a moment, or losing the family or the speed range,
 * ends it. Speeds here are actual speed, not the gravity-compensated effort the mode detector uses.
 *
 * Call once per game tick.
 */
public final class SteadyLoadDetector {
    private static final double TICKS_PER_SECOND = 20;

    private PackSettings.Steady settings = PackSettings.Steady.defaults();
    private int holdTicks;
    private int exitTicks;
    private double previousSpeed = Double.NaN;
    private final Family power = new Family();
    private final Family brake = new Family();

    public SteadyLoadDetector() {
        configure(PackSettings.Steady.defaults());
    }

    public void configure(PackSettings.Steady settings) {
        this.settings = settings;
        this.holdTicks = Math.max(1, (int) Math.round(settings.holdSeconds() * TICKS_PER_SECOND));
        this.exitTicks = Math.max(1, (int) Math.round(settings.exitHoldSeconds() * TICKS_PER_SECOND));
    }

    /**
     * @param speedMps train speed in metres per second
     * @param heard    the mode the voice plays, after contact and every other gate
     */
    public void update(double speedMps, TractionMode heard) {
        double speed = Math.abs(speedMps);
        double acceleration = Double.isNaN(previousSpeed) ? 0 : Math.abs(speed - previousSpeed) * TICKS_PER_SECOND;
        previousSpeed = speed;
        boolean inRange = speed >= settings.minSpeedMps() && speed <= settings.maxSpeedMps();
        power.update(heard == TractionMode.POWER && inRange, speed, acceleration);
        brake.update(heard == TractionMode.BRAKE && inRange, speed, acceleration);
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
        private int exiting;
        private double lowest;
        private double highest;

        private void update(boolean eligible, double speed, double acceleration) {
            if (!eligible) {
                steady = false;
                qualifying = 0;
                exiting = 0;
                return;
            }
            if (!steady) {
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
                    exiting = 0;
                }
            } else if (acceleration >= settings.exitAbsAccelerationMps2()) {
                if (++exiting >= exitTicks) {
                    steady = false;
                    qualifying = 0;
                    exiting = 0;
                }
            } else {
                // Between the entry and exit thresholds the train keeps whatever it had.
                exiting = 0;
            }
        }
    }
}
