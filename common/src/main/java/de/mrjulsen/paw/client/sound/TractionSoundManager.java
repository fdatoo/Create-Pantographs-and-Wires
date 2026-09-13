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
import net.minecraft.sounds.SoundEvent;

/**
 * Owns the traction sound for every vehicle carrying a pantograph. Backed by
 * {@link ElectricTrainStateTracker} so a train with several pantographs (or one
 * briefly skipping across an insulator gap) keeps one steady sound instead of
 * stuttering on and off.
 *
 * The sound is built from components measured in a recording of an MP 89, each
 * played as its own independently pitched voice: a rising low ridge, two fixed
 * departure tones, and a broadband bed. Every frequency here is an observed
 * acoustic feature, not a recovered electrical or switching parameter.
 *
 * The recording is a single acceleration, so its timeline is mapped onto train
 * speed rather than elapsed time: the ridge tracks speed across its measured
 * span, and the two tone centres fade past each other as the train pulls away.
 * That mapping is a design choice. The recording measures frequencies, not how
 * they should follow a throttle.
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

    // Speed (blocks/tick) at and beyond which the drive is at its top. Create's
    // configured maximum is 1.4 (28 m/s), but trains in practice cruise near half that.
    private static final double SPEED_AT_TOP = 0.7;
    // A standing train has no traction whine and no rolling noise, since nothing is
    // turning. Below this the whole bank fades out instead of humming at the platform.
    private static final double SPEED_AT_FULL_MOTION = 0.08;
    // Below this the vehicle counts as stopped and its voices are released outright.
    private static final double SPEED_CONSIDERED_STOPPED = 0.01;

    // Tone centres are fixed and never glide into one another, as measured. Only the
    // ridge moves. The 1372Hz partner is baked into the 686Hz sample at its measured
    // -6dB, and the ridge harmonics into the ridge sample, so locked ratios pitch as one.
    private static final double TONE_A_HZ = 686;
    private static final double TONE_B_HZ = 1186;
    private static final double RIDGE_REFERENCE_HZ = 256;
    private static final double RIDGE_AT_REST_HZ = 191;
    private static final double RIDGE_AT_TOP_HZ = 323;
    private static final float RIDGE_LEVEL = 0.9f;

    private static final double TEXTURE_REFERENCE_HZ = 1;
    private static final float TEXTURE_LEVEL = 0.5f;

    // Load envelope: the tonal layer swells while pulling and eases back once the vehicle
    // stops accelerating, leaving the mechanical texture underneath at a constant level.
    private static final double ACCEL_AT_FULL_LOAD = 0.004;
    private static final float CRUISE_LOAD = 0.75f;

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

        if (!snapshot.powered() || Math.abs(speed) < SPEED_CONSIDERED_STOPPED) {
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
            entry.update(speed);
        }
        if (touching) {
            entry.updatePosition(x, y, z);
        }
        entry.lastObservedTick = gameTime;
    }

    private static Entry startVoices(double speed, double x, double y, double z) {
        Entry entry = new Entry();
        entry.toneA = voice(ModSounds.MP89_TONE_A.get(), 1.0f, TONE_A_HZ, x, y, z);
        entry.toneB = voice(ModSounds.MP89_TONE_B.get(), 1.0f, TONE_B_HZ, x, y, z);
        entry.ridge = voice(ModSounds.MP89_RIDGE.get(), RIDGE_LEVEL, RIDGE_REFERENCE_HZ, x, y, z);
        entry.texture = voice(ModSounds.MP89_TEXTURE.get(), TEXTURE_LEVEL, TEXTURE_REFERENCE_HZ, x, y, z);
        entry.voices.addAll(List.of(entry.toneA, entry.toneB, entry.ridge, entry.texture));

        // Motor-body resonances belong to the structure, not the excitation, so the
        // texture never moves: tonal lines slide past fixed resonances.
        entry.texture.setTargetFrequency(TEXTURE_REFERENCE_HZ);
        // Frequencies are set before playback so no voice starts at its sample's own
        // pitch and audibly slides to where the vehicle's speed actually puts it.
        entry.update(speed);
        entry.voices.forEach(v -> Minecraft.getInstance().getSoundManager().play(v));
        return entry;
    }

    private static TractionHumSoundInstance voice(
        SoundEvent event, float level, double referenceHz, double x, double y, double z
    ) {
        return new TractionHumSoundInstance(event, level, referenceHz, x, y, z);
    }

    /** The low ridge, its measured span mapped onto the speed range. */
    public static double ridgeFrequency(double speed) {
        return RIDGE_AT_REST_HZ + (RIDGE_AT_TOP_HZ - RIDGE_AT_REST_HZ) * speedFraction(speed);
    }

    /** Overall level from speed, so a train standing at a platform falls silent. */
    static float motionScale(double speed) {
        return (float) Math.min(1, Math.max(0, Math.abs(speed) / SPEED_AT_FULL_MOTION));
    }

    /** Tonal level while pulling versus coasting, from acceleration in blocks/tick^2. */
    static float loadScaleFor(double acceleration) {
        double pull = Math.min(1, Math.max(0, acceleration / ACCEL_AT_FULL_LOAD));
        return (float) (CRUISE_LOAD + (1 - CRUISE_LOAD) * pull);
    }

    /** Trapezoid window over speed fraction, for fading one component past another. */
    static float window(double x, double riseFrom, double riseTo, double fallFrom, double fallTo) {
        if (x <= riseFrom || x >= fallTo) {
            return 0f;
        }
        if (x < riseTo) {
            return (float) ((x - riseFrom) / (riseTo - riseFrom));
        }
        if (x <= fallFrom) {
            return 1f;
        }
        return (float) ((fallTo - x) / (fallTo - fallFrom));
    }

    public static double speedFraction(double speed) {
        return Math.min(1, Math.max(0, Math.abs(speed) / SPEED_AT_TOP));
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

    /**
     * Whether this vehicle has a pantograph reporting at all, regardless of whether it is
     * currently touching wire. Used to decide that a train is electric rather than steam,
     * which should not flicker just because the collector crossed an insulator gap.
     */
    public static boolean isElectric(UUID vehicleId, long gameTime) {
        return TRACKER.snapshot(vehicleId, gameTime).capable();
    }

    /** Total voices currently sounding across all vehicles; for the F3 overlay. */
    public static int activeVoiceCount() {
        return ACTIVE.values().stream().mapToInt(entry -> entry.voices.size()).sum();
    }

    /** Call on disconnect/world unload so no voice survives into the next session. */
    public static void stopAll() {
        ACTIVE.values().forEach(Entry::stop);
        ACTIVE.clear();
        TRACKER.clear();
    }

    private static final class Entry {
        private final List<TractionHumSoundInstance> voices = new ArrayList<>();
        private TractionHumSoundInstance toneA;
        private TractionHumSoundInstance toneB;
        private TractionHumSoundInstance ridge;
        private TractionHumSoundInstance texture;
        private long lastObservedTick;
        private double previousSpeed;
        private boolean hasPreviousSpeed;

        private void update(double speed) {
            double acceleration = hasPreviousSpeed ? speed - previousSpeed : 0;
            previousSpeed = speed;
            hasPreviousSpeed = true;

            float motion = motionScale(speed);
            float tonal = motion * loadScaleFor(acceleration);
            double fraction = speedFraction(speed);

            ridge.setTargetFrequency(ridgeFrequency(speed));
            ridge.setLoadScale(tonal * window(fraction, 0.05, 0.30, 1.0, 1.01));

            // The 686Hz tone leads at departure, 1186Hz takes over, then the ridge carries.
            toneA.setTargetFrequency(TONE_A_HZ);
            toneA.setLoadScale(tonal * window(fraction, -1, 0.02, 0.15, 0.35));
            toneB.setTargetFrequency(TONE_B_HZ);
            toneB.setLoadScale(tonal * window(fraction, 0.18, 0.30, 0.45, 0.62));

            texture.setLoadScale(motion);
        }

        private void updatePosition(double x, double y, double z) {
            voices.forEach(voice -> voice.updatePosition(x, y, z));
        }

        private void stop() {
            voices.forEach(TractionHumSoundInstance::requestStop);
        }

        private boolean isStopped() {
            return ridge == null || ridge.isStopped();
        }
    }
}
