package de.mrjulsen.paw.client.sound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.mrjulsen.paw.registry.ModSounds;
import de.mrjulsen.paw.traction.ElectricTrainSnapshot;
import de.mrjulsen.paw.traction.ElectricTrainStateTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * Owns the traction sound for every vehicle carrying a pantograph. Backed by
 * {@link ElectricTrainStateTracker} so a train with several pantographs (or one
 * briefly skipping across an insulator gap) keeps one steady sound instead of
 * stuttering on and off.
 *
 * Each vehicle gets a small bank of independently pitched voices rather than a
 * single sample: a carrier at the switching frequency, one voice per PWM
 * sideband, a bass voice on the 2fe motor order, and a fixed-pitch texture bed.
 * Sidebands sit at fc +/- k*fe, so as electrical frequency rises with speed they
 * spread apart around a carrier that is meanwhile holding steady within its gear
 * -- the real behaviour, and not something one pitch-shifted sample can do.
 */
@Environment(EnvType.CLIENT)
public final class TractionSoundManager {
    // Bridges brief insulator gaps between wire spans without audibly cutting the sound.
    private static final long CONTACT_GRACE_TICKS = 10;
    private static final long CAPABILITY_GRACE_TICKS = 40;
    // A vehicle that stops reporting entirely (unloaded, contraption disassembled)
    // is swept out and its voices force-stopped after this many ticks of silence.
    private static final long STALE_AFTER_TICKS = 40;
    // Each vehicle costs one channel per voice, so cap how many sound at once.
    private static final int MAX_VEHICLES = 8;

    // Speed (blocks/tick) at and beyond which the drive reaches its top gear.
    private static final double SPEED_AT_TOP_GEAR = 0.5;

    // Switching frequency: the carrier sample is cut at this, and gear stepping scales it.
    private static final double CARRIER_REFERENCE_HZ = 1500;
    // Real IGBT drives hold switching frequency fairly steady within a speed band, then drop
    // and climb again at each pulse-pattern shift -- a rising staircase, not one smooth glide.
    private static final int GEAR_COUNT = 4;
    private static final double GEAR_BASE = 0.85;
    private static final double GEAR_RISE = 0.22;
    private static final double GEAR_DROP = 0.08;

    // Electrical frequency tracks motor RPM, so it climbs continuously with speed.
    private static final double FE_AT_REST_HZ = 25;
    private static final double FE_AT_TOP_HZ = 80;

    // The sideband sample is a bare tone cut at this; chosen so fc +/- 4fe across every
    // gear stays inside the engine's [0.5, 2.0] playback pitch clamp.
    private static final double SIDEBAND_REFERENCE_HZ = 1600;
    // Likewise for the bass voice: 2fe spans 50-160Hz, which against 90Hz is 0.56-1.78.
    private static final double BASS_REFERENCE_HZ = 90;
    private static final double TEXTURE_REFERENCE_HZ = 1;

    // Sideband orders and their level relative to the carrier: the inner pair (fc +/- 2fe)
    // sits ~15dB down, the outer pair (fc +/- 4fe) ~23dB down.
    private static final int[] SIDEBAND_ORDERS = { -4, -2, 2, 4 };
    private static final float INNER_SIDEBAND_LEVEL = 0.178f;
    private static final float OUTER_SIDEBAND_LEVEL = 0.0708f;
    // The bass voice rides the same 2fe order the inner sidebands are spaced by.
    private static final int BASS_ORDER = 2;
    private static final float BASS_LEVEL = 1.0f;

    // Load envelope: the tonal layer swells while pulling and eases back once the vehicle
    // stops accelerating, leaving the mechanical texture underneath at a constant level.
    private static final double ACCEL_AT_FULL_LOAD = 0.004;
    private static final float CRUISE_LOAD = 0.55f;

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
                entry.stop();
                ACTIVE.remove(vehicleId);
            }
            return;
        }

        if (entry == null || entry.isStopped()) {
            if (entry == null && ACTIVE.size() >= MAX_VEHICLES) {
                return;
            }
            entry = startVoices(speed, x, y, z);
            ACTIVE.put(vehicleId, entry);
        } else {
            entry.updateFrequencies(speed);
        }
        if (touching) {
            entry.updatePosition(x, y, z);
        }
        entry.lastObservedTick = gameTime;
    }

    private static Entry startVoices(double speed, double x, double y, double z) {
        Entry entry = new Entry();
        entry.carrier = new TractionHumSoundInstance(
            ModSounds.TRACTION_CARRIER.get(), 1.0f, CARRIER_REFERENCE_HZ, x, y, z);
        entry.voices.add(entry.carrier);

        for (int order : SIDEBAND_ORDERS) {
            float level = Math.abs(order) == 2 ? INNER_SIDEBAND_LEVEL : OUTER_SIDEBAND_LEVEL;
            TractionHumSoundInstance sideband = new TractionHumSoundInstance(
                ModSounds.TRACTION_SIDEBAND.get(), level, SIDEBAND_REFERENCE_HZ, x, y, z);
            entry.sidebands.add(sideband);
            entry.voices.add(sideband);
        }

        entry.bass = new TractionHumSoundInstance(
            ModSounds.TRACTION_BASS.get(), BASS_LEVEL, BASS_REFERENCE_HZ, x, y, z);
        entry.voices.add(entry.bass);

        TractionHumSoundInstance texture = new TractionHumSoundInstance(
            ModSounds.TRACTION_TEXTURE.get(), 1.0f, TEXTURE_REFERENCE_HZ, x, y, z);
        // Motor-body resonances are a property of the structure, not the excitation, so this
        // voice never moves: tonal lines slide past fixed resonances instead of dragging them.
        texture.setTargetFrequency(TEXTURE_REFERENCE_HZ);
        entry.voices.add(texture);

        // Frequencies are set before playback so no voice starts at its sample's own pitch
        // and audibly slides to where the vehicle's speed actually puts it.
        entry.updateFrequencies(speed);
        entry.voices.forEach(voice -> Minecraft.getInstance().getSoundManager().play(voice));
        return entry;
    }

    /** Switching frequency in Hz: stepped by gear, holding within each band. */
    static double switchingFrequency(double speed) {
        double fraction = speedFraction(speed);
        double gearProgress = fraction * GEAR_COUNT;
        int gearIndex = Math.min(GEAR_COUNT - 1, (int) gearProgress);
        double withinGear = gearProgress - gearIndex;
        double factor = GEAR_BASE + gearIndex * (GEAR_RISE - GEAR_DROP) + withinGear * GEAR_RISE;
        return CARRIER_REFERENCE_HZ * factor;
    }

    /** Electrical frequency in Hz: climbs continuously with motor RPM. */
    static double electricalFrequency(double speed) {
        return FE_AT_REST_HZ + (FE_AT_TOP_HZ - FE_AT_REST_HZ) * speedFraction(speed);
    }

    /** Tonal level while pulling versus coasting, from acceleration in blocks/tick^2. */
    static float loadScaleFor(double acceleration) {
        double pull = Math.min(1, Math.max(0, acceleration / ACCEL_AT_FULL_LOAD));
        return (float) (CRUISE_LOAD + (1 - CRUISE_LOAD) * pull);
    }

    private static double speedFraction(double speed) {
        return Math.min(1, Math.max(0, Math.abs(speed) / SPEED_AT_TOP_GEAR));
    }

    /** Call once per client tick to sweep vehicles that stopped reporting entirely. */
    public static void tick(long gameTime) {
        ACTIVE.entrySet().removeIf(mapEntry -> {
            Entry entry = mapEntry.getValue();
            if (entry.isStopped()) {
                return true;
            }
            if (gameTime - entry.lastObservedTick > STALE_AFTER_TICKS) {
                entry.stop();
                return true;
            }
            return false;
        });
    }

    /** Call on disconnect/world unload so no voice survives into the next session. */
    public static void stopAll() {
        ACTIVE.values().forEach(Entry::stop);
        ACTIVE.clear();
        TRACKER.clear();
    }

    private static final class Entry {
        private final List<TractionHumSoundInstance> voices = new ArrayList<>();
        private final List<TractionHumSoundInstance> sidebands = new ArrayList<>();
        private TractionHumSoundInstance carrier;
        private TractionHumSoundInstance bass;
        private long lastObservedTick;
        private double previousSpeed;
        private boolean hasPreviousSpeed;

        private void updateFrequencies(double speed) {
            double fc = switchingFrequency(speed);
            double fe = electricalFrequency(speed);
            carrier.setTargetFrequency(fc);
            for (int i = 0; i < sidebands.size(); i++) {
                sidebands.get(i).setTargetFrequency(fc + SIDEBAND_ORDERS[i] * fe);
            }
            bass.setTargetFrequency(BASS_ORDER * fe);

            double acceleration = hasPreviousSpeed ? speed - previousSpeed : 0;
            previousSpeed = speed;
            hasPreviousSpeed = true;
            float load = loadScaleFor(acceleration);
            carrier.setLoadScale(load);
            sidebands.forEach(sideband -> sideband.setLoadScale(load));
            bass.setLoadScale(load);
        }

        private void updatePosition(double x, double y, double z) {
            voices.forEach(voice -> voice.updatePosition(x, y, z));
        }

        private void stop() {
            voices.forEach(TractionHumSoundInstance::requestStop);
        }

        private boolean isStopped() {
            return carrier == null || carrier.isStopped();
        }
    }
}
