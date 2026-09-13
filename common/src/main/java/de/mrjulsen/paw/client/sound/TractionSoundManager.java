package de.mrjulsen.paw.client.sound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.mrjulsen.paw.config.ModClientConfig;
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
 * Two selectable profiles, both built from the same bank of independently
 * pitched voices:
 *
 *   inverter - derived from how IGBT drives behave: a gear-stepped switching
 *              frequency with PWM sidebands spreading around it as electrical
 *              frequency rises.
 *   mp89     - components measured from a recording of an MP 89. Its rising low
 *              ridge is mapped onto train speed, and its two departure tones
 *              fade in and out as the train pulls away. The measured ridge
 *              climbs smoothly, so this profile has no gear stepping.
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

    // Speed (blocks/tick) at and beyond which the drive reaches its top. Create's
    // configured maximum is 1.4 (28 m/s), but trains in practice cruise near half that.
    private static final double SPEED_AT_TOP_GEAR = 0.7;
    // A standing train has no traction whine and no rolling noise, since nothing is
    // turning. Below this the whole bank fades out instead of humming at the platform.
    private static final double SPEED_AT_FULL_MOTION = 0.08;
    // Below this the vehicle counts as stopped and its voices are released outright.
    private static final double SPEED_CONSIDERED_STOPPED = 0.01;

    // --- inverter profile ------------------------------------------------------
    private static final double CARRIER_REFERENCE_HZ = 1500;
    private static final int GEAR_COUNT = 4;
    private static final double GEAR_BASE = 0.85;
    private static final double GEAR_RISE = 0.34;
    private static final double GEAR_DROP = 0.18;
    private static final double FE_AT_REST_HZ = 25;
    private static final double FE_AT_TOP_HZ = 80;
    private static final double SIDEBAND_REFERENCE_HZ = 1600;
    private static final double BASS_REFERENCE_HZ = 90;
    private static final int[] SIDEBAND_ORDERS = { -4, -2, 2, 4 };
    private static final float INNER_SIDEBAND_LEVEL = 0.178f;
    private static final float OUTER_SIDEBAND_LEVEL = 0.0708f;
    private static final int BASS_ORDER = 2;
    private static final float BASS_LEVEL = 1.0f;

    // --- mp89 profile ----------------------------------------------------------
    // Observed acoustic features, not recovered electrical or switching parameters.
    // Tone centres are fixed and never glide between each other; only the ridge moves.
    private static final double MP89_TONE_A_HZ = 686;
    private static final double MP89_TONE_B_HZ = 1186;
    private static final double MP89_RIDGE_REFERENCE_HZ = 256;
    private static final double MP89_RIDGE_AT_REST_HZ = 191;
    private static final double MP89_RIDGE_AT_TOP_HZ = 323;
    private static final float MP89_RIDGE_LEVEL = 0.9f;

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

    private static boolean useMp89() {
        return "mp89".equals(ModClientConfig.TRACTION_PROFILE.get());
    }

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
            entry.updateFrequencies(speed);
        }
        if (touching) {
            entry.updatePosition(x, y, z);
        }
        entry.lastObservedTick = gameTime;
    }

    private static Entry startVoices(double speed, double x, double y, double z) {
        Entry entry = new Entry();
        entry.mp89 = useMp89();

        if (entry.mp89) {
            entry.toneA = voice(ModSounds.MP89_TONE_A.get(), 1.0f, MP89_TONE_A_HZ, x, y, z);
            entry.toneB = voice(ModSounds.MP89_TONE_B.get(), 1.0f, MP89_TONE_B_HZ, x, y, z);
            entry.ridge = voice(ModSounds.MP89_RIDGE.get(), MP89_RIDGE_LEVEL, MP89_RIDGE_REFERENCE_HZ, x, y, z);
            entry.texture = voice(ModSounds.MP89_TEXTURE.get(), TEXTURE_LEVEL, TEXTURE_REFERENCE_HZ, x, y, z);
            entry.primary = entry.ridge;
            entry.voices.addAll(List.of(entry.toneA, entry.toneB, entry.ridge, entry.texture));
        } else {
            entry.carrier = voice(ModSounds.TRACTION_CARRIER.get(), 1.0f, CARRIER_REFERENCE_HZ, x, y, z);
            entry.voices.add(entry.carrier);
            for (int order : SIDEBAND_ORDERS) {
                float level = Math.abs(order) == 2 ? INNER_SIDEBAND_LEVEL : OUTER_SIDEBAND_LEVEL;
                TractionHumSoundInstance sideband =
                    voice(ModSounds.TRACTION_SIDEBAND.get(), level, SIDEBAND_REFERENCE_HZ, x, y, z);
                entry.sidebands.add(sideband);
                entry.voices.add(sideband);
            }
            entry.bass = voice(ModSounds.TRACTION_BASS.get(), BASS_LEVEL, BASS_REFERENCE_HZ, x, y, z);
            entry.voices.add(entry.bass);
            entry.texture = voice(ModSounds.TRACTION_TEXTURE.get(), TEXTURE_LEVEL, TEXTURE_REFERENCE_HZ, x, y, z);
            entry.voices.add(entry.texture);
            entry.primary = entry.carrier;
        }

        // Motor-body resonances belong to the structure, not the excitation, so the
        // texture never moves: tonal lines slide past fixed resonances.
        entry.texture.setTargetFrequency(TEXTURE_REFERENCE_HZ);
        // Frequencies are set before playback so no voice starts at its sample's own
        // pitch and audibly slides to where the vehicle's speed actually puts it.
        entry.updateFrequencies(speed);
        entry.voices.forEach(v -> Minecraft.getInstance().getSoundManager().play(v));
        return entry;
    }

    private static TractionHumSoundInstance voice(
        net.minecraft.sounds.SoundEvent event, float level, double referenceHz,
        double x, double y, double z
    ) {
        return new TractionHumSoundInstance(event, level, referenceHz, x, y, z);
    }

    /** Which gear a given speed sits in; exposed so the F3 readout can show it. */
    public static int gearFor(double speed) {
        return Math.min(GEAR_COUNT - 1, (int) (speedFraction(speed) * GEAR_COUNT));
    }

    /** Switching frequency in Hz: stepped by gear, holding within each band. */
    public static double switchingFrequency(double speed) {
        double gearProgress = speedFraction(speed) * GEAR_COUNT;
        int gearIndex = gearFor(speed);
        double withinGear = gearProgress - gearIndex;
        double factor = GEAR_BASE + gearIndex * (GEAR_RISE - GEAR_DROP) + withinGear * GEAR_RISE;
        return CARRIER_REFERENCE_HZ * factor;
    }

    /** Electrical frequency in Hz: climbs continuously with motor RPM. */
    public static double electricalFrequency(double speed) {
        return FE_AT_REST_HZ + (FE_AT_TOP_HZ - FE_AT_REST_HZ) * speedFraction(speed);
    }

    /** The MP 89 low ridge, its measured span mapped onto the speed range. */
    static double mp89RidgeFrequency(double speed) {
        return MP89_RIDGE_AT_REST_HZ
            + (MP89_RIDGE_AT_TOP_HZ - MP89_RIDGE_AT_REST_HZ) * speedFraction(speed);
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
    private static float window(double x, double riseFrom, double riseTo, double fallFrom, double fallTo) {
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
        private final List<TractionHumSoundInstance> sidebands = new ArrayList<>();
        private boolean mp89;
        private TractionHumSoundInstance primary;
        private TractionHumSoundInstance carrier;
        private TractionHumSoundInstance bass;
        private TractionHumSoundInstance texture;
        private TractionHumSoundInstance toneA;
        private TractionHumSoundInstance toneB;
        private TractionHumSoundInstance ridge;
        private long lastObservedTick;
        private double previousSpeed;
        private boolean hasPreviousSpeed;
        private int previousGear = -1;

        private void updateFrequencies(double speed) {
            double acceleration = hasPreviousSpeed ? speed - previousSpeed : 0;
            previousSpeed = speed;
            hasPreviousSpeed = true;
            float motion = motionScale(speed);
            float tonal = motion * loadScaleFor(acceleration);

            if (mp89) {
                updateMp89(speed, motion, tonal);
            } else {
                updateInverter(speed, motion, tonal);
            }
        }

        private void updateInverter(double speed, float motion, float tonal) {
            double fc = switchingFrequency(speed);
            double fe = electricalFrequency(speed);
            carrier.setTargetFrequency(fc);
            for (int i = 0; i < sidebands.size(); i++) {
                sidebands.get(i).setTargetFrequency(fc + SIDEBAND_ORDERS[i] * fe);
            }
            bass.setTargetFrequency(BASS_ORDER * fe);

            // Gear changes jump rather than glide. Gliding spreads the drop across the
            // same window the climb is happening in, which cancels it out perceptually.
            int gear = gearFor(speed);
            if (previousGear >= 0 && gear != previousGear) {
                voices.forEach(TractionHumSoundInstance::snapToTarget);
            }
            previousGear = gear;

            carrier.setLoadScale(tonal);
            sidebands.forEach(sideband -> sideband.setLoadScale(tonal));
            bass.setLoadScale(tonal);
            texture.setLoadScale(motion);
        }

        private void updateMp89(double speed, float motion, float tonal) {
            // Only the ridge moves; the two tone centres are fixed, as measured.
            ridge.setTargetFrequency(mp89RidgeFrequency(speed));
            ridge.setLoadScale(tonal * window(speedFraction(speed), 0.05, 0.30, 1.0, 1.01));

            // The recording's timeline mapped onto speed rather than elapsed time: the
            // 686Hz tone leads at departure, 1186Hz takes over, then the ridge carries.
            // This mapping is a design choice, not something the recording measures.
            double fraction = speedFraction(speed);
            toneA.setLoadScale(tonal * window(fraction, -1, 0.02, 0.15, 0.35));
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
            return primary == null || primary.isStopped();
        }
    }
}
