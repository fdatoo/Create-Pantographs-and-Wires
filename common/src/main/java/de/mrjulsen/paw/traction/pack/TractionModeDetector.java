package de.mrjulsen.paw.traction.pack;

/**
 * Decides whether a train is powering, braking or coasting from how its speed changes, since Create
 * does not send a train's throttle to clients. Hysteresis keeps a train at steady acceleration from
 * flickering between modes; a train holding speed under power reads as coasting.
 */
public final class TractionModeDetector {
    /** How quickly the acceleration estimate follows each tick's change in speed. */
    public static final double SMOOTHING = 0.35;
    /** Acceleration (blocks per tick squared) that enters powering or braking. Create's default is 0.0075. */
    public static final double ENTER = 0.0008;
    /** Acceleration below which powering or braking gives way to coasting. */
    public static final double EXIT = 0.0004;

    private double previousSpeed;
    private boolean hasPreviousSpeed;
    private double acceleration;
    private TractionMode mode = TractionMode.COAST;

    /** Call once per game tick with the train's speed in blocks per tick. */
    public TractionMode update(double speed) {
        double change = hasPreviousSpeed ? speed - previousSpeed : 0;
        previousSpeed = speed;
        hasPreviousSpeed = true;
        acceleration += (change - acceleration) * SMOOTHING;
        mode = switch (mode) {
            case POWER -> acceleration < -ENTER ? TractionMode.BRAKE : acceleration < EXIT ? TractionMode.COAST : TractionMode.POWER;
            case BRAKE -> acceleration > ENTER ? TractionMode.POWER : acceleration > -EXIT ? TractionMode.COAST : TractionMode.BRAKE;
            case COAST -> acceleration > ENTER ? TractionMode.POWER : acceleration < -ENTER ? TractionMode.BRAKE : TractionMode.COAST;
        };
        return mode;
    }

    public TractionMode mode() {
        return mode;
    }
}
