package de.mrjulsen.paw.traction;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Which settings each train currently has, as reported every tick by the Traction Controllers riding on
 * it. A train with several controllers uses the one on the lowest-numbered carriage, then the lowest
 * position, so every player resolves the same one. Reports older than STALE_TICKS are ignored, so a
 * removed controller stops counting.
 */
public final class TrainSettingsRegistry {
    /** The client's registry, filled by controller movement behaviours. */
    public static final TrainSettingsRegistry CLIENT = new TrainSettingsRegistry();

    static final long STALE_TICKS = 40;

    private record Key(int carriage, long localPos) {}

    private record Report(TrainSettings settings, long tick) {}

    private static final Comparator<Key> ORDER = Comparator.comparingInt(Key::carriage).thenComparingLong(Key::localPos);

    private final Map<UUID, TreeMap<Key, Report>> reports = new HashMap<>();

    public synchronized void report(UUID trainId, int carriage, long localPos, TrainSettings settings, long tick) {
        reports.computeIfAbsent(trainId, id -> new TreeMap<>(ORDER)).put(new Key(carriage, localPos), new Report(settings, tick));
    }

    public synchronized TrainSettings of(UUID trainId, long tick) {
        TreeMap<Key, Report> train = reports.get(trainId);
        if (train == null) {
            return TrainSettings.DEFAULT;
        }
        train.values().removeIf(report -> tick - report.tick() > STALE_TICKS || tick < report.tick() - STALE_TICKS);
        if (train.isEmpty()) {
            reports.remove(trainId);
            return TrainSettings.DEFAULT;
        }
        return train.firstEntry().getValue().settings();
    }

    public synchronized void clear() {
        reports.clear();
    }
}
