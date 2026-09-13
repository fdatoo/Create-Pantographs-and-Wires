package de.mrjulsen.paw.client.sound;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import de.mrjulsen.paw.traction.ElectricTrainSnapshot;
import de.mrjulsen.paw.traction.ElectricTrainStateTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * Owns the looping electric traction hum for every vehicle carrying a
 * pantograph. Backed by {@link ElectricTrainStateTracker} so a train with
 * several pantographs (or one briefly skipping across an insulator gap)
 * keeps a single steady hum instead of one that stutters on and off.
 */
@Environment(EnvType.CLIENT)
public final class TractionSoundManager {
    // Bridges brief insulator gaps between wire spans without audibly cutting the hum.
    private static final long CONTACT_GRACE_TICKS = 10;
    private static final long CAPABILITY_GRACE_TICKS = 40;
    // A vehicle that stops reporting entirely (unloaded, contraption disassembled)
    // is swept out and its sound force-stopped after this many ticks of silence.
    private static final long STALE_AFTER_TICKS = 40;

    // Speed (blocks/tick) at and beyond which the whine reaches its top gear; a typical
    // cruising train sits well under this, so most of the range is used in practice.
    private static final double SPEED_AT_MAX_PITCH = 0.5;
    private static final float MIN_PITCH = 0.85f;
    // Real IGBT traction inverters hold their switching frequency fairly steady within a
    // speed band, then drop to a lower frequency and climb again each time the controller
    // shifts pulse pattern -- a rising staircase, not one smooth glide. GEAR_RISE is how far
    // pitch climbs across one band; GEAR_DROP is the step back down at the start of the next.
    private static final int GEAR_COUNT = 4;
    private static final float GEAR_RISE = 0.22f;
    private static final float GEAR_DROP = 0.08f;

    private static final ElectricTrainStateTracker TRACKER =
        new ElectricTrainStateTracker(CONTACT_GRACE_TICKS, CAPABILITY_GRACE_TICKS);
    private static final Map<UUID, Entry> ACTIVE = new HashMap<>();

    private TractionSoundManager() {}

    public static void observe(
        UUID vehicleId,
        long gameTime,
        boolean raised,
        boolean touching,
        double speed,
        double x,
        double y,
        double z
    ) {
        TRACKER.observe(vehicleId, gameTime, raised, touching);
        ElectricTrainSnapshot snapshot = TRACKER.snapshot(vehicleId, gameTime);
        Entry entry = ACTIVE.get(vehicleId);

        if (!snapshot.powered()) {
            if (entry != null) {
                entry.sound.requestStop();
                ACTIVE.remove(vehicleId);
            }
            return;
        }

        if (entry == null || entry.sound.isStopped()) {
            TractionHumSoundInstance sound = new TractionHumSoundInstance(x, y, z);
            entry = new Entry(sound);
            ACTIVE.put(vehicleId, entry);
            Minecraft.getInstance().getSoundManager().play(sound);
        }
        if (touching) {
            entry.sound.updatePosition(x, y, z);
        }
        entry.sound.setTargetPitch(pitchForSpeed(speed));
        entry.lastObservedTick = gameTime;
    }

    private static float pitchForSpeed(double speed) {
        double fraction = Math.min(1, Math.max(0, Math.abs(speed) / SPEED_AT_MAX_PITCH));
        double gearProgress = fraction * GEAR_COUNT;
        int gearIndex = Math.min(GEAR_COUNT - 1, (int) gearProgress);
        double withinGear = gearProgress - gearIndex;
        return (float) (MIN_PITCH + gearIndex * (GEAR_RISE - GEAR_DROP) + withinGear * GEAR_RISE);
    }

    /** Call once per client tick to sweep vehicles that stopped reporting entirely. */
    public static void tick(long gameTime) {
        ACTIVE.entrySet().removeIf(mapEntry -> {
            Entry entry = mapEntry.getValue();
            if (entry.sound.isStopped()) {
                return true;
            }
            if (gameTime - entry.lastObservedTick > STALE_AFTER_TICKS) {
                entry.sound.requestStop();
                return true;
            }
            return false;
        });
    }

    /** Call on disconnect/world unload so no hum survives into the next session. */
    public static void stopAll() {
        ACTIVE.values().forEach(entry -> entry.sound.requestStop());
        ACTIVE.clear();
        TRACKER.clear();
    }

    private static final class Entry {
        private final TractionHumSoundInstance sound;
        private long lastObservedTick;

        private Entry(TractionHumSoundInstance sound) {
            this.sound = sound;
        }
    }
}
