package de.mrjulsen.paw.traction;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * The client's store of train speeds reported by the server, one playout per train. Only touched on
 * the client thread: reports are queued there by the packet handler, and the sound code ticks it.
 */
public final class TractionSpeedFeed {
    private static final long FORGET_AFTER_TICKS = 200;
    private static final Map<UUID, TrainSpeedPlayout> PLAYOUTS = new HashMap<>();
    private static long now;

    private TractionSpeedFeed() {}

    public static void offer(UUID trainId, long serverTick, double speed) {
        PLAYOUTS.computeIfAbsent(trainId, id -> new TrainSpeedPlayout()).offer(serverTick, speed, now);
    }

    /** Call once per client tick. */
    public static void tick() {
        now++;
        PLAYOUTS.values().removeIf(playout -> playout.silentFor(now, FORGET_AFTER_TICKS));
        PLAYOUTS.values().forEach(playout -> playout.advance(now));
    }

    /** The server's speed for a train in blocks per tick, or empty when the server isn't reporting it. */
    public static OptionalDouble speed(UUID trainId) {
        TrainSpeedPlayout playout = PLAYOUTS.get(trainId);
        return playout != null && playout.available(now) ? OptionalDouble.of(playout.speed()) : OptionalDouble.empty();
    }

    public static void clear() {
        PLAYOUTS.clear();
    }
}
