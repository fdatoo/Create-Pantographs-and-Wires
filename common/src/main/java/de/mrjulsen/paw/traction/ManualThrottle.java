package de.mrjulsen.paw.traction;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side: which trains a player is driving by hand with a direction held, as Create's manual
 * control sees them each tick. A train that slows while its driver still holds forward is being held
 * back by Create's speed limits (curves, steep slopes), not braked.
 */
public final class ManualThrottle {
    private static final long FORGET_AFTER_TICKS = 100;
    private static final Map<UUID, Long> HELD = new ConcurrentHashMap<>();

    private ManualThrottle() {}

    /** Called from Create's manual control each tick it runs. */
    public static void record(UUID trainId, long tick, boolean held) {
        if (TractionDebug.server() && held != held(trainId, tick)) {
            TractionDebug.info("server: train {} driver {} a direction", TractionDebug.shortId(trainId), held ? "now holds" : "released");
        }
        if (held) {
            HELD.put(trainId, tick);
        } else {
            HELD.remove(trainId);
        }
        if (HELD.size() > 64) {
            HELD.values().removeIf(last -> tick - last > FORGET_AFTER_TICKS);
        }
    }

    /** Whether a direction was held this tick or the one before, since control may run after the carriage ticks. */
    public static boolean held(UUID trainId, long tick) {
        Long last = HELD.get(trainId);
        return last != null && tick - last <= 1;
    }
}
