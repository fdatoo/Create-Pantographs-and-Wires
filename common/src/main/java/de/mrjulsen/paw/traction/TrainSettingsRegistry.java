package de.mrjulsen.paw.traction;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Which settings each train has, as reported every tick by the Traction Controllers riding on it. A train
 * with several controllers has the most recently chosen settings; on the server, controllers holding
 * older ones are brought up to date (TractionControllerMovementBehaviour), so every controller on a train
 * shows the same. Equal times go to the lowest-numbered carriage, then the lowest position. Reports older
 * than STALE_TICKS are ignored, so a removed controller stops counting.
 */
public final class TrainSettingsRegistry {
    /** Filled by controllers on the client, read by the sound, steam and speedometer code. */
    public static final TrainSettingsRegistry CLIENT = new TrainSettingsRegistry();
    /** Filled by controllers on the server, to bring the train's other controllers up to date. */
    public static final TrainSettingsRegistry SERVER = new TrainSettingsRegistry();

    static final long STALE_TICKS = 40;

    /** Settings and when they were chosen, in milliseconds since the epoch (0 for never). */
    public record Stamped(TrainSettings settings, long changed) {}

    private record Key(int carriage, long localPos) {}

    private record Report(Stamped stamped, long tick) {}

    private static final Comparator<Key> ORDER = Comparator.comparingInt(Key::carriage).thenComparingLong(Key::localPos);

    private final Map<UUID, TreeMap<Key, Report>> reports = new HashMap<>();

    public synchronized void report(UUID trainId, int carriage, long localPos, Stamped stamped, long tick) {
        reports.computeIfAbsent(trainId, id -> new TreeMap<>(ORDER)).put(new Key(carriage, localPos), new Report(stamped, tick));
    }

    /** The train's settings, or empty when no controller on it has reported lately. */
    public synchronized Optional<Stamped> find(UUID trainId, long tick) {
        TreeMap<Key, Report> train = reports.get(trainId);
        if (train == null) {
            return Optional.empty();
        }
        train.values().removeIf(report -> tick - report.tick() > STALE_TICKS || tick < report.tick() - STALE_TICKS);
        if (train.isEmpty()) {
            reports.remove(trainId);
            return Optional.empty();
        }
        Stamped newest = null;
        for (Report report : train.values()) {
            if (newest == null || report.stamped().changed() > newest.changed()) {
                newest = report.stamped();
            }
        }
        return Optional.of(newest);
    }

    /** The train's settings, or DEFAULT when it has no controller. */
    public TrainSettings of(UUID trainId, long tick) {
        return find(trainId, tick).map(Stamped::settings).orElse(TrainSettings.DEFAULT);
    }

    public synchronized void clear() {
        reports.clear();
    }
}
