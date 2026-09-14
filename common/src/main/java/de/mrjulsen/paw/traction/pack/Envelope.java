package de.mrjulsen.paw.traction.pack;

/**
 * A sample-accurate gain envelope. A new target starts from the current gain, so an envelope
 * interrupted part-way (a quick power-to-brake reversal) moves on without a jump.
 */
final class Envelope {
    private final int sampleRate;
    private double gain;
    private double from;
    private double target;
    private long duration = 1;
    private long elapsed = 1;
    private PackSettings.Shape shape = PackSettings.Shape.LINEAR;

    Envelope(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    void setTarget(double newTarget, double seconds, PackSettings.Shape newShape) {
        if (newTarget == target) {
            return;
        }
        from = gain;
        target = newTarget;
        shape = newShape;
        duration = Math.max(1, Math.round(seconds * sampleRate));
        elapsed = 0;
    }

    /** Advances one sample and returns the gain for it. */
    double next() {
        if (elapsed < duration) {
            elapsed++;
            double u = elapsed / (double) duration;
            double s = shape == PackSettings.Shape.SMOOTHSTEP ? u * u * (3 - 2 * u) : u;
            gain = from + (target - from) * s;
        } else {
            gain = target;
        }
        return gain;
    }

    double gain() {
        return gain;
    }
}
