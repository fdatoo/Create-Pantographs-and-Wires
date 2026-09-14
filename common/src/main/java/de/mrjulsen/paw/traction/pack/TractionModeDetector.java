package de.mrjulsen.paw.traction.pack;

/**
 * Decides whether a train is powering, braking or coasting from how its speed changes, since Create
 * does not send a train's throttle to clients. Hysteresis keeps a train at steady acceleration from
 * flickering between modes; a train holding speed under power on the flat reads as coasting.
 *
 * A pack can also ask for persistence: while the train is moving, a new mode must hold for a few ticks
 * before it takes effect, so a brief blip of acceleration doesn't squeak a layer bank in and out.
 * Departing from rest is exempt.
 */
public final class TractionModeDetector {
    /** How quickly the acceleration estimate follows each tick's change in speed. */
    public static final double SMOOTHING = 0.35;
    /** Acceleration (blocks per tick squared) that enters powering or braking. Create's default is 0.0075. */
    public static final double ENTER = 0.0008;
    /** Acceleration below which powering or braking gives way to coasting. */
    public static final double EXIT = 0.0004;
    /** Gravity in blocks per tick squared, at 20 ticks per second. */
    public static final double GRAVITY = 9.81 / 400;

    private int persistenceTicks;
    private int coastPersistenceTicks;
    private double departureSpeed;

    private double previousSpeed;
    private boolean hasPreviousSpeed;
    private double drivenAcceleration;
    private double slopeAcceleration;
    private boolean slopeDriven;
    private TractionMode mode = TractionMode.COAST;
    private TractionMode pending;
    private int pendingTicks;

    /**
     * @param persistenceTicks ticks a new mode must hold while moving before it takes effect; 0 for at once
     * @param departureSpeed   speed (blocks per tick) below which changes take effect at once
     */
    public void configure(int persistenceTicks, double departureSpeed) {
        configure(persistenceTicks, persistenceTicks, departureSpeed);
    }

    /**
     * @param persistenceTicks      ticks a new power or brake mode must hold while moving before it takes effect
     * @param coastPersistenceTicks ticks the end of demand must hold while moving before cruising takes over;
     *                              longer, so demand handing from a slope to speeding up doesn't dip to cruise
     * @param departureSpeed        speed (blocks per tick) below which changes take effect at once
     */
    public void configure(int persistenceTicks, int coastPersistenceTicks, double departureSpeed) {
        this.persistenceTicks = Math.max(0, persistenceTicks);
        this.coastPersistenceTicks = Math.max(0, coastPersistenceTicks);
        this.departureSpeed = departureSpeed;
    }

    /** Call once per game tick with the train's speed in blocks per tick, on the flat. */
    public TractionMode update(double speed) {
        return update(speed, 0, false);
    }

    /** Call once per game tick, for a train with no driver holding a direction. */
    public TractionMode update(double speed, double grade) {
        return update(speed, grade, false);
    }

    /** Whether the slope, rather than the train's own speeding up or slowing down, brought in the current mode. */
    public boolean slopeDriven() {
        return slopeDriven;
    }

    /** Smoothed acceleration from the train's own speeding up or slowing down, in blocks per tick squared. */
    public double drivenAcceleration() {
        return drivenAcceleration;
    }

    /** Smoothed gravity along the slope, in blocks per tick squared; positive when climbing. */
    public double slopeAcceleration() {
        return slopeAcceleration;
    }

    /**
     * Call once per game tick.
     *
     * @param speed blocks per tick
     * @param grade rise over run along the direction of travel, positive when climbing. Create doesn't
     *              slow trains on hills, so gravity along the slope is added as the work the motors
     *              would be doing: holding speed on a climb reads as powering, on a descent as braking.
     * @param throttleHeld whether a driver holds a direction. A train that slows while its driver holds
     *              forward is being held back by one of Create's speed limits (a curve, a steep slope),
     *              not braked, so only gravity counts then.
     */
    public TractionMode update(double speed, double grade, boolean throttleHeld) {
        double change = hasPreviousSpeed ? speed - previousSpeed : 0;
        previousSpeed = speed;
        hasPreviousSpeed = true;
        double driven = throttleHeld && change < 0 ? 0 : change;
        double slope = speed > 0 ? GRAVITY * grade / Math.sqrt(1 + grade * grade) : 0;
        drivenAcceleration += (driven - drivenAcceleration) * SMOOTHING;
        slopeAcceleration += (slope - slopeAcceleration) * SMOOTHING;
        double acceleration = drivenAcceleration + slopeAcceleration;
        TractionMode before = mode;
        TractionMode candidate = switch (mode) {
            case POWER -> acceleration < -ENTER ? TractionMode.BRAKE : acceleration < EXIT ? TractionMode.COAST : TractionMode.POWER;
            case BRAKE -> acceleration > ENTER ? TractionMode.POWER : acceleration > -EXIT ? TractionMode.COAST : TractionMode.BRAKE;
            case COAST -> acceleration > ENTER ? TractionMode.POWER : acceleration < -ENTER ? TractionMode.BRAKE : TractionMode.COAST;
        };
        int needed = candidate == TractionMode.COAST ? coastPersistenceTicks : persistenceTicks;
        if (candidate == mode) {
            pending = null;
            pendingTicks = 0;
        } else if (needed == 0 || speed < departureSpeed) {
            mode = candidate;
            pending = null;
            pendingTicks = 0;
        } else {
            if (candidate != pending) {
                pending = candidate;
                pendingTicks = 0;
            }
            if (++pendingTicks >= needed) {
                mode = candidate;
                pending = null;
                pendingTicks = 0;
            }
        }
        if (mode != before) {
            slopeDriven = Math.abs(slopeAcceleration) > Math.abs(drivenAcceleration);
        }
        return mode;
    }

    public TractionMode mode() {
        return mode;
    }
}
