package de.mrjulsen.paw.client.sound;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.mrjulsen.paw.config.ModClientConfig;
import de.mrjulsen.paw.config.TractionSoundProfile;
import de.mrjulsen.paw.registry.ModSounds;
import de.mrjulsen.paw.traction.ElectricTrainSnapshot;
import de.mrjulsen.paw.traction.ElectricTrainStateTracker;
import de.mrjulsen.paw.traction.WmataTraction;
import de.mrjulsen.paw.traction.WmataTractionData;
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
 * Each vehicle plays a bank of independently pitched voices built from components
 * measured in a recording, chosen by the traction profile in the client config: the
 * WMATA 6000-series by default, or the Paris MP 89. Every frequency is an observed
 * acoustic feature, not a recovered electrical or switching parameter.
 *
 * Both recordings are a single acceleration, so their timelines are mapped onto train
 * speed rather than elapsed time: standing is the start of the departure and cruise
 * is its end. That mapping is a design choice. A recording measures frequencies, not
 * how they should follow a throttle.
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

    // Load envelope: the tonal layer swells while pulling and eases back once the vehicle
    // stops accelerating, leaving the mechanical texture underneath at a constant level.
    private static final double ACCEL_AT_FULL_LOAD = 0.004;
    private static final float CRUISE_LOAD = 0.75f;

    // Noise samples have no pitch to track; a reference of 1Hz driven to 1Hz holds them still.
    private static final double UNPITCHED_REFERENCE_HZ = 1;

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

        // A profile changed in the config takes over on the next observation: the old
        // bank fades out on its own while the new one fades in.
        TractionSoundProfile profile = ModClientConfig.TRACTION_PROFILE.get();
        if (entry != null && entry.profile != profile) {
            entry.stop();
            ACTIVE.remove(vehicleId);
            entry = null;
        }

        if (entry == null || entry.isStopped()) {
            if (entry == null && ACTIVE.size() >= MAX_VEHICLES) {
                return;
            }
            entry = start(profile, speed, x, y, z);
            ACTIVE.put(vehicleId, entry);
        } else {
            entry.update(speed);
        }
        if (touching) {
            entry.updatePosition(x, y, z);
        }
        entry.lastObservedTick = gameTime;
    }

    private static Entry start(TractionSoundProfile profile, double speed, double x, double y, double z) {
        VoiceBank bank = switch (profile) {
            case MP89 -> new Mp89Bank(x, y, z);
            case WMATA -> new WmataBank(x, y, z);
        };
        Entry entry = new Entry(profile, bank);
        // Frequencies are set before playback so no voice starts at its sample's own
        // pitch and audibly slides to where the vehicle's speed actually puts it.
        entry.update(speed);
        bank.voices().forEach(v -> Minecraft.getInstance().getSoundManager().play(v));
        return entry;
    }

    private static TractionHumSoundInstance voice(
        SoundEvent event, float level, double referenceHz, double x, double y, double z
    ) {
        return new TractionHumSoundInstance(event, level, referenceHz, x, y, z);
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

    /** Call on disconnect/world unload so no voice survives into the next session. */
    public static void stopAll() {
        ACTIVE.values().forEach(Entry::stop);
        ACTIVE.clear();
        TRACKER.clear();
    }

    private static final class Entry {
        private final TractionSoundProfile profile;
        private final VoiceBank bank;
        private long lastObservedTick;
        private double previousSpeed;
        private boolean hasPreviousSpeed;

        private Entry(TractionSoundProfile profile, VoiceBank bank) {
            this.profile = profile;
            this.bank = bank;
        }

        private void update(double speed) {
            double acceleration = hasPreviousSpeed ? speed - previousSpeed : 0;
            previousSpeed = speed;
            hasPreviousSpeed = true;

            float motion = motionScale(speed);
            bank.update(speedFraction(speed), motion, motion * loadScaleFor(acceleration));
        }

        private void updatePosition(double x, double y, double z) {
            bank.voices().forEach(voice -> voice.updatePosition(x, y, z));
        }

        private void stop() {
            bank.voices().forEach(TractionHumSoundInstance::requestStop);
        }

        private boolean isStopped() {
            return bank.voices().get(0).isStopped();
        }
    }

    /** One profile's voices and how they follow the vehicle. */
    private interface VoiceBank {
        List<TractionHumSoundInstance> voices();

        /**
         * @param fraction speed from standing (0) to the drive's top (1)
         * @param motion   overall level from speed, for mechanical layers
         * @param tonal    motion scaled by load, for the traction tones
         */
        void update(double fraction, float motion, float tonal);
    }

    /**
     * Paris MP 89: a rising low ridge, two fixed departure tones, and a broadband bed.
     *
     * Tone centres are fixed and never glide into one another, as measured. Only the ridge
     * moves. The 1372Hz partner is baked into the 686Hz sample at its measured -6dB, and the
     * ridge harmonics into the ridge sample, so locked ratios pitch as one.
     */
    private static final class Mp89Bank implements VoiceBank {
        private static final double TONE_A_HZ = 686;
        private static final double TONE_B_HZ = 1186;
        private static final double RIDGE_REFERENCE_HZ = 256;
        private static final double RIDGE_AT_REST_HZ = 191;
        private static final double RIDGE_AT_TOP_HZ = 323;
        private static final float RIDGE_LEVEL = 0.9f;
        private static final float TEXTURE_LEVEL = 0.5f;

        private final TractionHumSoundInstance toneA;
        private final TractionHumSoundInstance toneB;
        private final TractionHumSoundInstance ridge;
        private final TractionHumSoundInstance texture;
        private final List<TractionHumSoundInstance> voices;

        private Mp89Bank(double x, double y, double z) {
            toneA = voice(ModSounds.MP89_TONE_A.get(), 1.0f, TONE_A_HZ, x, y, z);
            toneB = voice(ModSounds.MP89_TONE_B.get(), 1.0f, TONE_B_HZ, x, y, z);
            ridge = voice(ModSounds.MP89_RIDGE.get(), RIDGE_LEVEL, RIDGE_REFERENCE_HZ, x, y, z);
            texture = voice(ModSounds.MP89_TEXTURE.get(), TEXTURE_LEVEL, UNPITCHED_REFERENCE_HZ, x, y, z);
            voices = List.of(toneA, toneB, ridge, texture);
            // Motor-body resonances belong to the structure, not the excitation, so the
            // texture never moves: tonal lines slide past fixed resonances.
            texture.setTargetFrequency(UNPITCHED_REFERENCE_HZ);
        }

        @Override
        public List<TractionHumSoundInstance> voices() {
            return voices;
        }

        @Override
        public void update(double fraction, float motion, float tonal) {
            ridge.setTargetFrequency(RIDGE_AT_REST_HZ + (RIDGE_AT_TOP_HZ - RIDGE_AT_REST_HZ) * fraction);
            ridge.setLoadScale(tonal * window(fraction, 0.05, 0.30, 1.0, 1.01));

            // The 686Hz tone leads at departure, 1186Hz takes over, then the ridge carries.
            toneA.setTargetFrequency(TONE_A_HZ);
            toneA.setLoadScale(tonal * window(fraction, -1, 0.02, 0.15, 0.35));
            toneB.setTargetFrequency(TONE_B_HZ);
            toneB.setLoadScale(tonal * window(fraction, 0.18, 0.30, 0.45, 0.62));

            texture.setLoadScale(motion);
        }
    }

    /**
     * WMATA 6000-series: an upper cluster near 2.4-2.6kHz that turns diffuse as the train
     * gathers speed, a ridge climbing from about 440Hz to 1.6kHz that swells twice, a brief
     * upper event, and a rising noise bed. Curves and levels live in {@link WmataTraction}.
     */
    private static final class WmataBank implements VoiceBank {
        private final TractionHumSoundInstance upperLine;
        private final TractionHumSoundInstance upperDiffuse;
        private final TractionHumSoundInstance ridge;
        private final TractionHumSoundInstance brief;
        private final TractionHumSoundInstance noise;
        private final List<TractionHumSoundInstance> voices;

        private WmataBank(double x, double y, double z) {
            upperLine = voice(ModSounds.WMATA_UPPER_LINE.get(), 1.0f, WmataTractionData.UPPER_REFERENCE_HZ, x, y, z);
            upperDiffuse = voice(ModSounds.WMATA_UPPER_DIFFUSE.get(), 1.0f, WmataTractionData.UPPER_REFERENCE_HZ, x, y, z);
            ridge = voice(ModSounds.WMATA_RIDGE.get(), 1.0f, WmataTractionData.RIDGE_REFERENCE_HZ, x, y, z);
            brief = voice(ModSounds.WMATA_BRIEF.get(), 1.0f, WmataTractionData.BRIEF_REFERENCE_HZ, x, y, z);
            noise = voice(ModSounds.WMATA_NOISE.get(), 1.0f, UNPITCHED_REFERENCE_HZ, x, y, z);
            voices = List.of(upperLine, upperDiffuse, ridge, brief, noise);
            noise.setTargetFrequency(UNPITCHED_REFERENCE_HZ);
        }

        @Override
        public List<TractionHumSoundInstance> voices() {
            return voices;
        }

        @Override
        public void update(double fraction, float motion, float tonal) {
            double t = WmataTraction.timeForSpeedFraction(fraction);

            double upper = WmataTraction.upperFrequency(t);
            upperLine.setTargetFrequency(upper);
            upperLine.setLoadScale(tonal * (float) WmataTraction.upperLineVolume(t));
            upperDiffuse.setTargetFrequency(upper);
            upperDiffuse.setLoadScale(tonal * (float) WmataTraction.upperDiffuseVolume(t));

            ridge.setTargetFrequency(WmataTraction.ridgeFrequency(t));
            ridge.setLoadScale(tonal * (float) WmataTraction.ridgeVolume(t));
            brief.setTargetFrequency(WmataTraction.briefFrequency(t));
            brief.setLoadScale(tonal * (float) WmataTraction.briefVolume(t));

            noise.setLoadScale(motion * (float) WmataTraction.noiseVolume(t));
        }
    }
}
