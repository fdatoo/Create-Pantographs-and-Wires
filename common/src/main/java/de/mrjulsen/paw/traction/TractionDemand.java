package de.mrjulsen.paw.traction;

/**
 * How hard a train's traction is working, estimated from how its speed changes. Create does not send
 * a train's throttle or target speed to clients, so acceleration is the only signal: speeding up means
 * the motors are powering, slowing down hard means they are braking, and holding speed or drifting
 * slowly down counts as coasting. Both demands are smoothed so client-side speed jitter doesn't make
 * the sound flicker, and fade back out gradually once the train stops accelerating or braking.
 *
 * Pure logic; tools/sound/wmata/simulate_ingame.py mirrors it exactly for offline renders.
 */
public final class TractionDemand {
    /** How quickly the acceleration estimate follows each tick's change in speed. */
    public static final double ACCELERATION_SMOOTHING = 0.25;
    /** Acceleration (blocks per tick squared) below which a train is treated as coasting. */
    public static final double DEAD_ZONE = 0.0008;
    /** Acceleration at which demand is full. Create's default train acceleration is 0.0075. */
    public static final double FULL_DEMAND = 0.004;
    /** Per-tick approach rates: demand builds within a few ticks and eases off over about a second. */
    public static final double RISE = 0.15;
    public static final double FALL = 0.06;

    private double previousSpeed;
    private boolean hasPreviousSpeed;
    private double acceleration;
    private double power;
    private double brake;

    /** Call once per game tick with the vehicle's speed in blocks per tick. */
    public void update(double speed) {
        double change = hasPreviousSpeed ? speed - previousSpeed : 0;
        previousSpeed = speed;
        hasPreviousSpeed = true;
        acceleration += (change - acceleration) * ACCELERATION_SMOOTHING;
        power = approach(power, ramp(acceleration));
        brake = approach(brake, ramp(-acceleration));
    }

    /** Traction demand while powering, 0 (coasting) to 1 (full acceleration). */
    public double power() {
        return power;
    }

    /** Braking demand, 0 (coasting) to 1 (full deceleration). */
    public double brake() {
        return brake;
    }

    static double ramp(double acceleration) {
        return Math.min(1, Math.max(0, (acceleration - DEAD_ZONE) / (FULL_DEMAND - DEAD_ZONE)));
    }

    private static double approach(double current, double target) {
        return current + (target - current) * (target > current ? RISE : FALL);
    }
}
