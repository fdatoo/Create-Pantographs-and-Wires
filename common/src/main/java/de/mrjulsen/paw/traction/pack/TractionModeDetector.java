package de.mrjulsen.paw.traction.pack;

/**
 * Decides whether a train is powering, braking or coasting from how its speed changes, since Create
 * does not send a train's throttle to clients. Hysteresis keeps a train at steady acceleration from
 * flickering between modes; a train holding speed under power reads as coasting.
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

    private int persistenceTicks;
    private double departureSpeed;

    private double previousSpeed;
    private boolean hasPreviousSpeed;
    private double acceleration;
    private TractionMode mode = TractionMode.COAST;
    private TractionMode pending;
    private int pendingTicks;

    /**
     * @param persistenceTicks ticks a new mode must hold while moving before it takes effect; 0 for at once
     * @param departureSpeed   speed (blocks per tick) below which changes take effect at once
     */
    public void configure(int persistenceTicks, double departureSpeed) {
        this.persistenceTicks = Math.max(0, persistenceTicks);
        this.departureSpeed = departureSpeed;
    }

    /** Call once per game tick with the train's speed in blocks per tick. */
    public TractionMode update(double speed) {
        double change = hasPreviousSpeed ? speed - previousSpeed : 0;
        previousSpeed = speed;
        hasPreviousSpeed = true;
        acceleration += (change - acceleration) * SMOOTHING;
        TractionMode candidate = switch (mode) {
            case POWER -> acceleration < -ENTER ? TractionMode.BRAKE : acceleration < EXIT ? TractionMode.COAST : TractionMode.POWER;
            case BRAKE -> acceleration > ENTER ? TractionMode.POWER : acceleration > -EXIT ? TractionMode.COAST : TractionMode.BRAKE;
            case COAST -> acceleration > ENTER ? TractionMode.POWER : acceleration < -ENTER ? TractionMode.BRAKE : TractionMode.COAST;
        };
        if (candidate == mode) {
            pending = null;
            pendingTicks = 0;
        } else if (persistenceTicks == 0 || speed < departureSpeed) {
            mode = candidate;
            pending = null;
            pendingTicks = 0;
        } else {
            if (candidate != pending) {
                pending = candidate;
                pendingTicks = 0;
            }
            if (++pendingTicks >= persistenceTicks) {
                mode = candidate;
                pending = null;
                pendingTicks = 0;
            }
        }
        return mode;
    }

    public TractionMode mode() {
        return mode;
    }
}
