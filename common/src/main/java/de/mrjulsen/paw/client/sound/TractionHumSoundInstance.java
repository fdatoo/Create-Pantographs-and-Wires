package de.mrjulsen.paw.client.sound;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * One voice of the traction sound: a looping tone or texture that follows a
 * moving vehicle and can be driven to an absolute frequency rather than a bare
 * pitch multiplier. Several of these together make up one vehicle's whine, so
 * that PWM sidebands can spread apart around a carrier that is itself holding
 * still -- something a single pitch-shifted sample cannot do, since shifting a
 * sample scales every frequency in it by the same ratio.
 *
 * Output level is base * fade * load: the fade envelope handles start and stop,
 * while the load scale lets the tonal voices swell under acceleration and ease
 * back at cruise without touching the mechanical texture underneath.
 */
@Environment(EnvType.CLIENT)
public class TractionHumSoundInstance extends AbstractTickableSoundInstance {

    // Create's own train loops play in SoundSource.NEUTRAL at up to 1.5x on full-scale
    // samples, so anything much below unity here is simply buried underneath them.
    private static final float MASTER_VOLUME = 1.0f;
    // 10 ticks (0.5s) to fade fully in or out.
    private static final float FADE_STEP = 1f / 10f;
    // How quickly pitch glides toward its target each tick; smaller = slower spool-up.
    private static final float PITCH_SMOOTHING = 0.08f;
    // Load responds slower than pitch, so per-tick speed jitter doesn't pump the level.
    private static final float LOAD_SMOOTHING = 0.05f;
    // Minecraft's sound engine clamps playback pitch to this window, so every voice's
    // reference frequency is chosen to keep its working range inside it.
    private static final float MIN_ENGINE_PITCH = 0.5f;
    private static final float MAX_ENGINE_PITCH = 2.0f;

    private final float baseVolume;
    private final double referenceFrequency;

    private boolean active = true;
    private float fade = 0f;
    private float targetPitch = 1.0f;
    private boolean pitchInitialised = false;
    private float loadScale = 1.0f;
    private float targetLoadScale = 1.0f;

    /**
     * @param level              this voice's share of the mix, 0-1, relative to the carrier
     * @param referenceFrequency the frequency baked into this voice's sample, used to turn
     *                           an absolute target frequency into a playback pitch ratio
     */
    public TractionHumSoundInstance(
        SoundEvent event,
        float level,
        double referenceFrequency,
        double x,
        double y,
        double z
    ) {
        super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.baseVolume = MASTER_VOLUME * level;
        this.referenceFrequency = referenceFrequency;
        this.looping = true;
        this.delay = 0;
        this.volume = 0f;
        this.pitch = 1.0f;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void updatePosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Drives this voice to an absolute frequency in Hz, clamped to what the engine can play.
     * The first call snaps rather than glides, so a voice never audibly slides from its
     * sample's own pitch down to whatever the vehicle's actual speed calls for.
     */
    public void setTargetFrequency(double frequencyHz) {
        float ratio = (float) (frequencyHz / referenceFrequency);
        this.targetPitch = Math.min(MAX_ENGINE_PITCH, Math.max(MIN_ENGINE_PITCH, ratio));
        if (!pitchInitialised) {
            this.pitch = this.targetPitch;
            this.pitchInitialised = true;
        }
    }

    /** Scales this voice's level, for swelling the tonal layer under acceleration. */
    public void setLoadScale(float scale) {
        this.targetLoadScale = scale;
    }

    public void requestStop() {
        this.active = false;
    }

    @Override
    public void tick() {
        this.pitch += (this.targetPitch - this.pitch) * PITCH_SMOOTHING;
        this.loadScale += (this.targetLoadScale - this.loadScale) * LOAD_SMOOTHING;
        this.fade = active
            ? Math.min(1f, this.fade + FADE_STEP)
            : Math.max(0f, this.fade - FADE_STEP);
        this.volume = baseVolume * this.fade * this.loadScale;

        if (!active && this.fade <= 0f) {
            this.stop();
        }
    }
}
