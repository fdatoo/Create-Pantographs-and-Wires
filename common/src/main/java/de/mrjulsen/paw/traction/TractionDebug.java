package de.mrjulsen.paw.traction;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.mrjulsen.paw.config.ModClientConfig;
import de.mrjulsen.paw.config.ModServerConfig;

/**
 * Diagnostic logging for electric traction, on the client (the sound) and the server (what it sends,
 * and Create's hand driving). Off unless switched on with a config option or a command; every line
 * starts with "[PAW traction]" so it can be pulled out of a log with grep.
 */
public final class TractionDebug {
    private static final Logger LOG = LoggerFactory.getLogger("PAW traction");
    private static final String PREFIX = "[PAW traction] ";

    private static volatile Boolean clientOverride;
    private static volatile Boolean serverOverride;
    private static final Set<String> ONCE = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Object, Long>> EDGES = new HashMap<>();

    private TractionDebug() {}

    /** Whether the client logs the traction sound: the command's setting if used, else the client config. */
    public static boolean client() {
        Boolean override = clientOverride;
        if (override != null) {
            return override;
        }
        try {
            return ModClientConfig.TRACTION_DEBUG.get();
        } catch (RuntimeException notLoaded) {
            return false;
        }
    }

    /** Whether the server logs traction: the command's setting if used, else the server config. */
    public static boolean server() {
        Boolean override = serverOverride;
        if (override != null) {
            return override;
        }
        try {
            return ModServerConfig.TRACTION_DEBUG.get();
        } catch (RuntimeException notLoaded) {
            return false;
        }
    }

    public static void setClient(boolean enabled) {
        clientOverride = enabled;
        info("client logging {}", enabled ? "on" : "off");
    }

    public static void setServer(boolean enabled) {
        serverOverride = enabled;
        info("server logging {}", enabled ? "on" : "off");
    }

    public static void info(String format, Object... args) {
        LOG.info(PREFIX + format, args);
    }

    /** Logs a line the first time a key is seen, whether or not debugging is on. */
    public static void once(String key, String message) {
        if (ONCE.add(key)) {
            LOG.info(PREFIX + message);
        }
    }

    /** True at most once per interval for a key, for rate-limited lines. */
    public static boolean every(String key, long tick, long interval) {
        Long last = LAST.get(key);
        if (last != null && tick - last < interval && tick >= last) {
            return false;
        }
        LAST.put(key, tick);
        return true;
    }

    /** True when a train meets a track edge it hasn't reported in the last 40 ticks. */
    public static synchronized boolean newEdge(UUID trainId, Object edge, long tick) {
        Map<Object, Long> seen = EDGES.computeIfAbsent(trainId, id -> new HashMap<>());
        seen.values().removeIf(last -> tick - last > 40 || tick < last);
        boolean fresh = !seen.containsKey(edge);
        seen.put(edge, tick);
        return fresh;
    }

    /** First eight hex digits of an id, enough to tell trains apart in a log. */
    public static String shortId(UUID id) {
        return id == null ? "--------" : id.toString().substring(0, 8);
    }
}
