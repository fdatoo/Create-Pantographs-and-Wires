package de.mrjulsen.paw.client.sound;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

import de.mrjulsen.paw.client.sound.synth.SynthAudioStream;
import de.mrjulsen.paw.client.sound.synth.SynthStreams;
import de.mrjulsen.paw.config.ModClientConfig;
import de.mrjulsen.paw.config.TractionSoundProfile;
import de.mrjulsen.paw.registry.ModSounds;
import de.mrjulsen.paw.traction.ElectricTrainSnapshot;
import de.mrjulsen.paw.traction.ElectricTrainStateTracker;
import de.mrjulsen.paw.traction.TractionSpeedFeed;
import de.mrjulsen.paw.traction.pack.TractionMixer;
import de.mrjulsen.paw.traction.pack.TractionMode;
import de.mrjulsen.paw.traction.pack.TractionModeDetector;
import de.mrjulsen.paw.traction.pack.TractionPack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;

/**
 * Owns the traction sound for every vehicle carrying a pantograph or collector shoe. Backed by
 * {@link ElectricTrainStateTracker} so a train with several collectors (or one
 * briefly skipping across an insulator gap) keeps one steady sound instead of
 * stuttering on and off.
 *
 * The traction profile in the client config picks how a vehicle sounds. WMATA (the default) plays the
 * WMATA traction sound pack (assets/pantographsandwires/traction/wmata): hand-made loops with pitch and
 * volume curves against speed, mixed live into one streamed voice per vehicle. MP89 plays pitched
 * loops of components measured in a recording of a Paris MP 89, laid out over speed.
 */
@Environment(EnvType.CLIENT)
public final class TractionSoundManager {
    // Bridges brief insulator gaps between wire spans without audibly cutting the sound.
    private static final long CONTACT_GRACE_TICKS = 10;
    private static final long CAPABILITY_GRACE_TICKS = 40;
    // A vehicle that stops reporting entirely (unloaded, contraption disassembled)
    // is swept out and its voices force-stopped after this many ticks of silence.
    private static final long STALE_AFTER_TICKS = 40;
    // Each vehicle costs channels, so cap how many sound at once. A streamed voice takes one of
    // the sound engine's few streaming channels, which music also uses, so leave some free.
    private static final int MAX_VEHICLES = 6;

    // MP 89: speed (blocks/tick) at and beyond which the drive is at its top.
    private static final double SPEED_AT_TOP = 0.7;
    // MP 89: below this the whole bank fades out instead of humming at the platform.
    private static final double SPEED_AT_FULL_MOTION = 0.08;
    // Below this the vehicle counts as stopped and its voices are released outright.
    private static final double SPEED_CONSIDERED_STOPPED = 0.01;
    private static final double METRES_PER_SECOND_PER_BLOCK_PER_TICK = 20;

    // MP 89 load envelope: the tonal layer swells while pulling and eases back once the vehicle
    // stops accelerating, leaving the mechanical texture underneath at a constant level.
    private static final double ACCEL_AT_FULL_LOAD = 0.004;
    private static final float CRUISE_LOAD = 0.75f;

    // Noise samples have no pitch to track; a reference of 1Hz driven to 1Hz holds them still.
    private static final double UNPITCHED_REFERENCE_HZ = 1;

    private static final ElectricTrainStateTracker TRACKER =
        new ElectricTrainStateTracker(CONTACT_GRACE_TICKS, CAPABILITY_GRACE_TICKS);
    private static final Map<UUID, Entry> ACTIVE = new HashMap<>();
    private static final Map<UUID, VehicleSpeed> SPEEDS = new HashMap<>();

    // A vehicle must read as stopped this long before its sound is released, so a train creeping
    // away from a platform doesn't start and stop its voice.
    private static final int STOPPED_AFTER_TICKS = 5;
    // Without the server's speed, the client's lurching motion is averaged over this many ticks
    // (four of Create's carriage updates).
    private static final int FALLBACK_WINDOW_TICKS = 12;

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
        VehicleSpeed vehicleSpeed = SPEEDS.computeIfAbsent(vehicleId, id -> new VehicleSpeed());
        vehicleSpeed.update(vehicleId, gameTime, speed);
        speed = vehicleSpeed.speed;

        boolean stopped = vehicleSpeed.stoppedTicks > 0 && (entry == null || vehicleSpeed.stoppedTicks >= STOPPED_AFTER_TICKS);
        if (!snapshot.powered() || stopped) {
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
        } else if (entry.lastUpdateTick != gameTime) {
            // A train reports once per collector each tick. Only the first report moves the sound, or
            // the rest would see no change in speed and read the train as coasting.
            entry.update(speed, gameTime);
        }
        entry.lastUpdateTick = gameTime;
        if (touching) {
            entry.bank.updatePosition(x, y, z);
        }
        entry.lastObservedTick = gameTime;
    }

    private static Entry start(TractionSoundProfile profile, double speed, double x, double y, double z) {
        VoiceBank bank = switch (profile) {
            case MP89 -> new Mp89Bank(x, y, z);
            case WMATA -> new PackBank(x, y, z);
        };
        Entry entry = new Entry(profile, bank);
        // State is set before playback so nothing starts at the wrong pitch and slides into place.
        entry.update(speed, Long.MIN_VALUE);
        bank.start();
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

    /** MP 89 tonal level while pulling versus coasting, from acceleration in blocks/tick^2. */
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
        TractionSpeedFeed.tick();
        SPEEDS.values().removeIf(vehicleSpeed -> Math.abs(gameTime - vehicleSpeed.lastTick) > STALE_AFTER_TICKS);
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
     * Whether this vehicle has a collector reporting at all, regardless of whether it is
     * currently touching wire or rail. Used to decide that a train is electric rather than steam,
     * which should not flicker just because the collector crossed an insulator gap.
     */
    public static boolean isElectric(UUID vehicleId, long gameTime) {
        return TRACKER.snapshot(vehicleId, gameTime).capable();
    }

    /** Call on disconnect/world unload so no voice survives into the next session. */
    public static void stopAll() {
        ACTIVE.values().forEach(Entry::stop);
        ACTIVE.clear();
        SPEEDS.clear();
        TractionSpeedFeed.clear();
        TRACKER.clear();
    }

    /**
     * One vehicle's speed as the sound uses it, worked out once per tick. The server's exact figure
     * when it reports one (see TractionSpeedSync); otherwise the client's own motion, which lurches
     * with every carriage update, averaged over several updates.
     */
    private static final class VehicleSpeed {
        private final double[] recent = new double[FALLBACK_WINDOW_TICKS];
        private int recentCount;
        private int recentNext;
        private long lastTick = Long.MIN_VALUE;
        private double speed;
        private int stoppedTicks;

        private void update(UUID vehicleId, long gameTime, double clientMotion) {
            if (gameTime == lastTick) {
                return;
            }
            lastTick = gameTime;
            recent[recentNext] = Math.abs(clientMotion);
            recentNext = (recentNext + 1) % recent.length;
            recentCount = Math.min(recentCount + 1, recent.length);

            OptionalDouble reported = TractionSpeedFeed.speed(vehicleId);
            if (reported.isPresent()) {
                speed = reported.getAsDouble();
            } else {
                double sum = 0;
                for (int i = 0; i < recentCount; i++) {
                    sum += recent[i];
                }
                speed = sum / recentCount;
            }
            stoppedTicks = speed < SPEED_CONSIDERED_STOPPED ? stoppedTicks + 1 : 0;
        }
    }

    private static final class Entry {
        private final TractionSoundProfile profile;
        private final VoiceBank bank;
        private long lastObservedTick;
        private long lastUpdateTick = Long.MIN_VALUE;
        private double previousSpeed;
        private boolean hasPreviousSpeed;

        private Entry(TractionSoundProfile profile, VoiceBank bank) {
            this.profile = profile;
            this.bank = bank;
        }

        private void update(double speed, long gameTime) {
            double acceleration = hasPreviousSpeed ? speed - previousSpeed : 0;
            previousSpeed = speed;
            hasPreviousSpeed = true;
            bank.update(speed, motionScale(speed), motionScale(speed) * loadScaleFor(acceleration), gameTime);
        }

        private void stop() {
            bank.stop();
        }

        private boolean isStopped() {
            return bank.isStopped();
        }
    }

    /** One profile's sound for a vehicle. */
    private interface VoiceBank {
        void start();

        /**
         * @param speed  blocks per tick
         * @param motion overall level from speed (MP 89)
         * @param tonal  motion scaled by MP 89's load envelope
         */
        void update(double speed, float motion, float tonal, long gameTime);

        void updatePosition(double x, double y, double z);

        void stop();

        boolean isStopped();
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
        public void start() {
            voices.forEach(v -> Minecraft.getInstance().getSoundManager().play(v));
        }

        @Override
        public void update(double speed, float motion, float tonal, long gameTime) {
            double fraction = speedFraction(speed);
            ridge.setTargetFrequency(RIDGE_AT_REST_HZ + (RIDGE_AT_TOP_HZ - RIDGE_AT_REST_HZ) * fraction);
            ridge.setLoadScale(tonal * window(fraction, 0.05, 0.30, 1.0, 1.01));

            // The 686Hz tone leads at departure, 1186Hz takes over, then the ridge carries.
            toneA.setTargetFrequency(TONE_A_HZ);
            toneA.setLoadScale(tonal * window(fraction, -1, 0.02, 0.15, 0.35));
            toneB.setTargetFrequency(TONE_B_HZ);
            toneB.setLoadScale(tonal * window(fraction, 0.18, 0.30, 0.45, 0.62));

            texture.setLoadScale(motion);
        }

        @Override
        public void updatePosition(double x, double y, double z) {
            voices.forEach(voice -> voice.updatePosition(x, y, z));
        }

        @Override
        public void stop() {
            voices.forEach(TractionHumSoundInstance::requestStop);
        }

        @Override
        public boolean isStopped() {
            return toneA.isStopped();
        }
    }

    /**
     * WMATA: the traction sound pack mixed live into one streamed voice. The pack loads in the
     * background; until it is ready the vehicle is silent and the voice starts as soon as it can. If
     * Minecraft drops the voice (a stalled client can starve the stream), it is restarted with the same
     * mixer, so every loop continues where it was.
     */
    private static final class PackBank implements VoiceBank {
        private static final long RESTART_INTERVAL_TICKS = 20;
        // The pack's own envelopes shape onsets and releases; the voice only needs to avoid a hard edge.
        private static final int VOICE_FADE_TICKS = 2;

        private final TractionModeDetector detector = new TractionModeDetector();
        private TractionMixer mixer;
        private TractionSynthSoundInstance instance;
        private double x;
        private double y;
        private double z;
        private double speedMps;
        private boolean stopping;
        private long lastStartTick = Long.MIN_VALUE;

        private PackBank(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void start() {
            if (mixer == null) {
                TractionPack pack = TractionPacks.wmataIfLoaded();
                if (pack == null) {
                    return;
                }
                mixer = new TractionMixer(pack, SynthAudioStream.SAMPLE_RATE);
                detector.configure((int) Math.round(pack.settings().modePersistenceSeconds() * 20),
                    pack.settings().departureSpeedMps() / METRES_PER_SECOND_PER_BLOCK_PER_TICK);
                mixer.setState(speedMps, detector.mode());
            }
            instance = new TractionSynthSoundInstance(x, y, z, VOICE_FADE_TICKS);
            SynthStreams.play(instance, new SynthAudioStream(mixer::render));
        }

        @Override
        public void update(double speed, float motion, float tonal, long gameTime) {
            speedMps = Math.abs(speed) * METRES_PER_SECOND_PER_BLOCK_PER_TICK;
            TractionMode mode = detector.update(Math.abs(speed));
            if (mixer != null) {
                mixer.setState(speedMps, mode);
            }
            if (!stopping && gameTime != Long.MIN_VALUE && gameTime - lastStartTick >= RESTART_INTERVAL_TICKS
                && (instance == null || !Minecraft.getInstance().getSoundManager().isActive(instance))) {
                lastStartTick = gameTime;
                start();
            }
        }

        @Override
        public void updatePosition(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            if (instance != null) {
                instance.updatePosition(x, y, z);
            }
        }

        @Override
        public void stop() {
            stopping = true;
            if (instance != null) {
                instance.requestStop();
            }
        }

        @Override
        public boolean isStopped() {
            return stopping && (instance == null || instance.isStopped() || !Minecraft.getInstance().getSoundManager().isActive(instance));
        }
    }
}
