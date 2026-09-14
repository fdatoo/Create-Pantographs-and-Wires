package de.mrjulsen.paw.client.sound;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import de.mrjulsen.paw.client.sound.synth.SynthAudioStream;
import de.mrjulsen.paw.client.sound.synth.SynthStreams;
import de.mrjulsen.paw.config.ModClientConfig;
import de.mrjulsen.paw.config.TractionSoundProfile;
import de.mrjulsen.paw.registry.ModSounds;
import de.mrjulsen.paw.traction.ElectricTrainSnapshot;
import de.mrjulsen.paw.traction.ElectricTrainStateTracker;
import de.mrjulsen.paw.traction.TractionDebug;
import de.mrjulsen.paw.traction.TractionSpeedFeed;
import de.mrjulsen.paw.traction.pack.TractionMixer;
import de.mrjulsen.paw.traction.pack.TractionMode;
import de.mrjulsen.paw.traction.pack.TractionModeDetector;
import de.mrjulsen.paw.traction.pack.TractionPack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
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
    // Bridges brief contact gaps (insulators between wire spans, a pantograph leaving the wire for a
    // moment on a curving slope) without the traction dropping out. Longer gaps drop to cruising.
    private static final long CONTACT_GRACE_TICKS = 30;
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
    // The server's speed is exact, so any movement it reports starts the sound at once; the client's
    // own average needs SPEED_CONSIDERED_STOPPED as a margin.
    private static final double SPEED_CONSIDERED_MOVING_REPORTED = 1e-4;
    // How far outside a carriage's blocks the listener still counts as aboard: a step off the side,
    // and standing on the roof.
    private static final double ABOARD_MARGIN = 0.5;
    private static final double ABOARD_MARGIN_VERTICAL = 1.0;
    // How long a sounding train may run without collector contact before its voice is released.
    private static final long CONTACT_RELEASE_TICKS = 200;

    private TractionSoundManager() {}

    public static void observe(
        UUID vehicleId,
        long gameTime,
        boolean raised,
        boolean touching,
        double speed,
        double x,
        double y,
        double z,
        boolean listenerAboard
    ) {
        TRACKER.observe(vehicleId, gameTime, raised, touching);
        ElectricTrainSnapshot snapshot = TRACKER.snapshot(vehicleId, gameTime);
        Entry entry = ACTIVE.get(vehicleId);
        VehicleSpeed vehicleSpeed = SPEEDS.computeIfAbsent(vehicleId, id -> new VehicleSpeed());
        vehicleSpeed.update(vehicleId, gameTime, speed, listenerAboard);
        speed = vehicleSpeed.speed;

        boolean debug = TractionDebug.client();
        if (debug && vehicleSpeed.aboard != vehicleSpeed.loggedAboard) {
            vehicleSpeed.loggedAboard = vehicleSpeed.aboard;
            TractionDebug.info("client: train {} listener is now {}", TractionDebug.shortId(vehicleId),
                vehicleSpeed.aboard ? "aboard (sound centres in both ears)" : "outside (sound placed at the nearest collector)");
        }

        boolean stopped = vehicleSpeed.stoppedTicks > 0 && (entry == null || vehicleSpeed.stoppedTicks >= STOPPED_AFTER_TICKS);
        // Losing contact while a voice plays (a gap in the wire, a pantograph leaving it on a slope or
        // curve) takes the traction off but keeps the voice, so it doesn't cut out and restart. Only a
        // long loss releases it, and a train that never had contact doesn't start one.
        if (snapshot.powered()) {
            vehicleSpeed.lastPoweredTick = gameTime;
        }
        boolean contactLost = !snapshot.powered() && (entry == null || vehicleSpeed.lastPoweredTick == Long.MIN_VALUE
            || gameTime - vehicleSpeed.lastPoweredTick > CONTACT_RELEASE_TICKS);
        if (contactLost || stopped) {
            if (entry != null) {
                if (debug) {
                    TractionDebug.info("client: train {} voice stopping: {}", TractionDebug.shortId(vehicleId),
                        stopped ? String.format("train stopped (%.3f m/s)", speed * METRES_PER_SECOND_PER_BLOCK_PER_TICK)
                            : "no collector touching wire or rail for " + CONTACT_RELEASE_TICKS / 20 + " s");
                }
                entry.stop();
                ACTIVE.remove(vehicleId);
            }
            return;
        }
        vehicleSpeed.powered = snapshot.powered();
        if (debug && entry != null && vehicleSpeed.powered != vehicleSpeed.loggedPowered) {
            TractionDebug.info("client: train {} {}", TractionDebug.shortId(vehicleId), vehicleSpeed.powered
                ? "collector contact restored, traction back on"
                : "collector contact lost, traction off (voice kept)");
        }
        vehicleSpeed.loggedPowered = vehicleSpeed.powered;

        // A profile changed in the config takes over on the next observation: the old
        // bank fades out on its own while the new one fades in.
        TractionSoundProfile profile = ModClientConfig.TRACTION_PROFILE.get();
        if (entry != null && entry.profile != profile) {
            if (debug) {
                TractionDebug.info("client: train {} profile changed to {}, restarting its voice", TractionDebug.shortId(vehicleId), profile);
            }
            entry.stop();
            ACTIVE.remove(vehicleId);
            entry = null;
        }

        if (entry == null || entry.isStopped()) {
            if (entry == null && ACTIVE.size() >= MAX_VEHICLES) {
                if (debug && TractionDebug.every("max-vehicles", gameTime, 200)) {
                    TractionDebug.info("client: train {} not played: {} trains already sounding", TractionDebug.shortId(vehicleId), MAX_VEHICLES);
                }
                return;
            }
            entry = start(profile, vehicleId, vehicleSpeed, x, y, z);
            ACTIVE.put(vehicleId, entry);
            if (debug) {
                TractionDebug.info("client: train {} voice started ({}) at {} m/s, speed from {}, listener {}", TractionDebug.shortId(vehicleId),
                    profile, String.format("%.3f", speed * METRES_PER_SECOND_PER_BLOCK_PER_TICK), vehicleSpeed.source(), vehicleSpeed.aboard ? "aboard" : "outside");
            }
        } else if (entry.lastUpdateTick != gameTime) {
            // A train reports once per collector each tick. Only the first report moves the sound, or
            // the rest would see no change in speed and read the train as coasting.
            entry.update(vehicleSpeed, gameTime);
        }
        entry.lastUpdateTick = gameTime;
        // The voice sits at the touching collector nearest the listener, so it can't hop between
        // collectors at either end or side of the train.
        if (touching && vehicleSpeed.nearestCollectorSoFar(gameTime, x, y, z)) {
            entry.bank.updatePosition(x, y, z);
        }
        entry.bank.setListenerAboard(vehicleSpeed.aboard);
        entry.lastObservedTick = gameTime;

        if (debug && TractionDebug.every("summary-" + vehicleId, gameTime, 20)) {
            entry.lastSummary = String.format("%.2f m/s, grade %+.1f%%, driver holding %s, contact %s, speed from %s, listener %s | %s",
                speed * METRES_PER_SECOND_PER_BLOCK_PER_TICK, vehicleSpeed.grade * 100, vehicleSpeed.throttleHeld ? "yes" : "no",
                vehicleSpeed.powered ? "yes" : "no", vehicleSpeed.source(), vehicleSpeed.aboard ? "aboard" : "outside", entry.bank.describe(gameTime));
            TractionDebug.info("client: train {} {}", TractionDebug.shortId(vehicleId), entry.lastSummary);
        }
    }

    /** F3 screen lines for every sounding train, while traction logging is on. */
    public static void debugLines(List<String> lines) {
        ACTIVE.forEach((id, entry) -> {
            if (entry.lastSummary.isEmpty()) {
                return;
            }
            String prefix = "[PAW traction] " + TractionDebug.shortId(id) + " ";
            for (String part : entry.lastSummary.split(" \\| ")) {
                lines.add(prefix + part);
                prefix = "    ";
            }
        });
    }

    private static Entry start(TractionSoundProfile profile, UUID vehicleId, VehicleSpeed vehicle, double x, double y, double z) {
        VoiceBank bank = switch (profile) {
            case MP89 -> new Mp89Bank(x, y, z);
            case WMATA -> new PackBank(vehicleId, x, y, z);
        };
        Entry entry = new Entry(profile, bank);
        // State is set before playback so nothing starts at the wrong pitch, or off to one side, and
        // slides into place.
        entry.update(vehicle, Long.MIN_VALUE);
        bank.setListenerAboard(vehicle.aboard);
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
        /** Rise over run along the direction of travel; 0 without the server's figures. */
        private double grade;
        /** Whether a driver holds a direction; false without the server's figures. */
        private boolean throttleHeld;
        private int stoppedTicks;
        /** Whether the listener is on this vehicle, from any report this tick. */
        private boolean aboard;
        private long positionTick = Long.MIN_VALUE;
        private double positionDistance;
        private boolean reported;
        private Boolean loggedReported;
        private boolean loggedAboard;
        /** Whether a collector touches wire or rail (within the tracker's grace). */
        private boolean powered = true;
        private boolean loggedPowered = true;
        private long lastPoweredTick = Long.MIN_VALUE;

        private String source() {
            return reported ? "server" : "client estimate";
        }

        private void update(UUID vehicleId, long gameTime, double clientMotion, boolean listenerAboard) {
            if (gameTime == lastTick) {
                aboard |= listenerAboard;
                return;
            }
            lastTick = gameTime;
            aboard = listenerAboard;
            recent[recentNext] = Math.abs(clientMotion);
            recentNext = (recentNext + 1) % recent.length;
            recentCount = Math.min(recentCount + 1, recent.length);

            Optional<TractionSpeedFeed.Report> reported = TractionSpeedFeed.reported(vehicleId);
            if (reported.isPresent()) {
                speed = reported.get().speed();
                grade = reported.get().grade();
                throttleHeld = reported.get().throttleHeld();
            } else {
                double sum = 0;
                for (int i = 0; i < recentCount; i++) {
                    sum += recent[i];
                }
                speed = sum / recentCount;
                grade = 0;
                throttleHeld = false;
            }
            this.reported = reported.isPresent();
            if (TractionDebug.client() && !Boolean.valueOf(this.reported).equals(loggedReported)) {
                loggedReported = this.reported;
                TractionDebug.info("client: train {} speed now {}", TractionDebug.shortId(vehicleId), this.reported
                    ? "from the server's reports"
                    : "estimated from client motion (no server reports: is the server running this mod version?)");
            }
            double stoppedBelow = reported.isPresent() ? SPEED_CONSIDERED_MOVING_REPORTED : SPEED_CONSIDERED_STOPPED;
            stoppedTicks = speed < stoppedBelow ? stoppedTicks + 1 : 0;
        }

        /** Whether a collector here is the nearest to the listener of those reported so far this tick. */
        private boolean nearestCollectorSoFar(long gameTime, double x, double y, double z) {
            double distance = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceToSqr(x, y, z);
            if (gameTime != positionTick || distance < positionDistance) {
                positionTick = gameTime;
                positionDistance = distance;
                return true;
            }
            return false;
        }
    }

    /** Whether the local player rides or stands on this vehicle, checked against every carriage of a train. */
    public static boolean listenerAboard(AbstractContraptionEntity entity) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        if (entity instanceof CarriageContraptionEntity carriageEntity && carriageEntity.getCarriage() != null
            && carriageEntity.getCarriage().train != null) {
            for (Carriage carriage : carriageEntity.getCarriage().train.carriages) {
                CarriageContraptionEntity other = carriage.anyAvailableEntity();
                if (other != null && aboard(player, other)) {
                    return true;
                }
            }
            return false;
        }
        return aboard(player, entity);
    }

    private static boolean aboard(LocalPlayer player, AbstractContraptionEntity entity) {
        if (player.getVehicle() == entity) {
            return true;
        }
        if (entity.getContraption() == null || entity.getContraption().bounds == null) {
            return false;
        }
        Vec3 local = entity.toLocalVector(player.position(), 1);
        return entity.getContraption().bounds.inflate(ABOARD_MARGIN, ABOARD_MARGIN_VERTICAL, ABOARD_MARGIN).contains(local);
    }

    private static final class Entry {
        private final TractionSoundProfile profile;
        private final VoiceBank bank;
        private long lastObservedTick;
        private long lastUpdateTick = Long.MIN_VALUE;
        private String lastSummary = "";
        private double previousSpeed;
        private boolean hasPreviousSpeed;

        private Entry(TractionSoundProfile profile, VoiceBank bank) {
            this.profile = profile;
            this.bank = bank;
        }

        private void update(VehicleSpeed vehicle, long gameTime) {
            double speed = vehicle.speed;
            double acceleration = hasPreviousSpeed ? speed - previousSpeed : 0;
            previousSpeed = speed;
            hasPreviousSpeed = true;
            bank.update(speed, vehicle.grade, vehicle.throttleHeld, vehicle.powered, motionScale(speed), motionScale(speed) * loadScaleFor(acceleration), gameTime);
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
        void update(double speed, double grade, boolean throttleHeld, boolean powered, float motion, float tonal, long gameTime);

        void updatePosition(double x, double y, double z);

        /** Whether the listener is on the vehicle, which centres the sound in both ears. */
        void setListenerAboard(boolean aboard);

        void stop();

        boolean isStopped();

        /** What the voice is doing, for traction logging. */
        String describe(long gameTime);
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
        public void update(double speed, double grade, boolean throttleHeld, boolean powered, float motion, float tonal, long gameTime) {
            if (!powered) {
                tonal = 0;
            }
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
        public void setListenerAboard(boolean aboard) {
            voices.forEach(voice -> voice.setListenerAboard(aboard));
        }

        @Override
        public void stop() {
            voices.forEach(TractionHumSoundInstance::requestStop);
        }

        @Override
        public boolean isStopped() {
            return toneA.isStopped();
        }

        @Override
        public String describe(long gameTime) {
            return "MP89 profile (no pack diagnostics)";
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
        // The pack's own envelopes shape onsets and releases, so a new voice starts at full volume and the
        // departure is heard at once. Fading is only for stopping, and for restarting a voice mid-sound.
        private static final int VOICE_FADE_TICKS = 2;

        private final UUID vehicleId;
        private final TractionModeDetector detector = new TractionModeDetector();
        /** The mode the voice plays: the detector's, or cruising while contact is lost. */
        private TractionMode heardMode = TractionMode.COAST;
        private TractionMixer mixer;
        private TractionSynthSoundInstance instance;
        private double x;
        private double y;
        private double z;
        private double speedMps;
        private boolean listenerAboard;
        private boolean stopping;
        private long lastStartTick = Long.MIN_VALUE;
        private long lastDescribeTick = Long.MIN_VALUE;
        private long lastDescribeBlocks;

        private PackBank(UUID vehicleId, double x, double y, double z) {
            this.vehicleId = vehicleId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void start() {
            boolean restart = mixer != null;
            if (mixer == null) {
                TractionPack pack = TractionPacks.wmataIfLoaded();
                if (pack == null) {
                    if (TractionDebug.client()) {
                        TractionDebug.info("client: train {} waiting for the WMATA sound pack to load", TractionDebug.shortId(vehicleId));
                    }
                    return;
                }
                mixer = new TractionMixer(pack, SynthAudioStream.SAMPLE_RATE);
                int persistenceTicks = (int) Math.round(pack.settings().modePersistenceSeconds() * 20);
                int endPersistenceTicks = pack.settings().modeEndPersistenceSeconds() > 0
                    ? (int) Math.round(pack.settings().modeEndPersistenceSeconds() * 20)
                    : persistenceTicks;
                detector.configure(persistenceTicks, endPersistenceTicks,
                    pack.settings().departureSpeedMps() / METRES_PER_SECOND_PER_BLOCK_PER_TICK);
                mixer.setState(speedMps, heardMode, detector.slopeDriven());
            }
            instance = new TractionSynthSoundInstance(x, y, z, restart ? VOICE_FADE_TICKS : 0, VOICE_FADE_TICKS);
            instance.setListenerAboard(listenerAboard);
            SynthStreams.play(instance, new SynthAudioStream(mixer::render));
        }

        @Override
        public void update(double speed, double grade, boolean throttleHeld, boolean powered, float motion, float tonal, long gameTime) {
            speedMps = Math.abs(speed) * METRES_PER_SECOND_PER_BLOCK_PER_TICK;
            TractionMode before = heardMode;
            TractionMode mode = detector.update(Math.abs(speed), grade, throttleHeld);
            if (!powered) {
                // No contact, no traction: only the rolling noise and the neutral tone.
                mode = TractionMode.COAST;
            }
            heardMode = mode;
            if (mode != before && TractionDebug.client()) {
                TractionDebug.info("client: train {} mode {} -> {} ({}) at {} m/s, grade {}%, driver holding {}, own acceleration {} m/s², gravity along slope {} m/s²",
                    TractionDebug.shortId(vehicleId), before, mode,
                    !powered ? "collector contact lost" : mode == TractionMode.COAST ? "demand ended" : detector.slopeDriven() ? "brought in by the slope" : "brought in by speed change",
                    String.format("%.2f", speedMps), String.format("%+.1f", grade * 100), throttleHeld ? "yes" : "no",
                    String.format("%+.2f", detector.drivenAcceleration() * 400), String.format("%+.2f", detector.slopeAcceleration() * 400));
            }
            if (mixer != null) {
                mixer.setState(speedMps, mode, detector.slopeDriven());
            }
            if (!stopping && gameTime != Long.MIN_VALUE && gameTime - lastStartTick >= RESTART_INTERVAL_TICKS
                && (instance == null || !Minecraft.getInstance().getSoundManager().isActive(instance))) {
                if (instance != null && TractionDebug.client()) {
                    TractionDebug.info("client: train {} voice was dropped by the sound engine (stream ran dry or no free channel), restarting",
                        TractionDebug.shortId(vehicleId));
                }
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
        public void setListenerAboard(boolean aboard) {
            listenerAboard = aboard;
            if (instance != null) {
                instance.setListenerAboard(aboard);
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

        @Override
        public String describe(long gameTime) {
            if (mixer == null) {
                return "WMATA pack not loaded yet";
            }
            TractionMixer.Diagnostics d = mixer.diagnostics(4);
            String stream = "-";
            if (lastDescribeTick != Long.MIN_VALUE && gameTime > lastDescribeTick) {
                double seconds = (gameTime - lastDescribeTick) / 20.0;
                double audioSeconds = (d.blocksRendered() - lastDescribeBlocks) * (double) SynthAudioStream.BLOCK_SAMPLES / SynthAudioStream.SAMPLE_RATE;
                stream = String.format("%.2fx real time", audioSeconds / seconds);
            }
            lastDescribeTick = gameTime;
            lastDescribeBlocks = d.blocksRendered();
            boolean playing = instance != null && Minecraft.getInstance().getSoundManager().isActive(instance);
            String ear = instance == null ? "-" : String.format("right %+.1f, up %+.1f, behind %+.1f blocks, centred %.0f%%",
                instance.getX(), instance.getY(), instance.getZ(), instance.aboardBlend() * 100);
            return String.format("mode %s%s, last change over %.2f s (%s) | envelopes power %.2f, brake %.2f, coast %.2f, cruising duck %.2f"
                    + " | loudest %s | voice %s, stream %s | ear %s",
                d.mode(), d.mode() != TractionMode.COAST && detector.slopeDriven() ? " (slope)" : "",
                d.modeChangeSeconds(), d.modeChangeMoving() ? "moving blend" : "departure timing",
                d.power(), d.brake(), d.coast(), d.duck(),
                d.loudest().isEmpty() ? "none" : String.join(", ", d.loudest()),
                playing ? "playing" : "not playing", stream, ear);
        }
    }
}
