package de.mrjulsen.paw.client.sound;

import de.mrjulsen.paw.config.ModClientConfig;
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

    // Read from config rather than fixed, so loudness can be tuned in game instead of
    // by rebuilding. Applied once at construction; voices are rebuilt per contact anyway.
    private static float masterVolume() {
        return ModClientConfig.TRACTION_VOLUME.get().floatValue();
    }
    // 10 ticks (0.5s) to fade fully in or out.
    private static final float FADE_STEP = 1f / 10f;
    // How quickly pitch glides toward its target each tick. Fast enough that a gear step
    // lands in about a fifth of a second and reads as a step; slower than this and the
    // steps smear together into one continuous rise.
    private static final float PITCH_SMOOTHING = 0.22f;
    // Load responds slower than pitch, so per-tick speed jitter doesn't pump the level.
    // Falling is much faster than rising: a train that stops should go quiet promptly,
    // not coast down over several seconds the way a symmetric filter would.
    private static final float LOAD_RISE = 0.05f;
    private static final float LOAD_FALL = 0.25f;
    // Minecraft's sound engine clamps playback pitch to this window, so every voice's
    // reference frequency is chosen to keep its working range inside it.
    private static final float MIN_ENGINE_PITCH = 0.5f;
    private static final float MAX_ENGINE_PITCH = 2.0f;

    private final float baseVolume;
    private final double referenceFrequency;
    private final ListenerRelativePlacement placement;

    private boolean active = true;
    private float fade = 0f;
    private float targetPitch = 1.0f;
    private boolean pitchInitialised = false;
    private boolean loadInitialised = false;
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
        this.baseVolume = masterVolume() * level;
        this.referenceFrequency = referenceFrequency;
        this.looping = true;
        this.delay = 0;
        this.volume = 0f;
        this.pitch = 1.0f;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        // Placed relative to the listener, so it can be centred while the listener rides the train.
        this.relative = true;
        this.placement = new ListenerRelativePlacement(x, y, z);
        place();
    }

    public void updatePosition(double x, double y, double z) {
        placement.setWorldPosition(x, y, z);
    }

    public void setListenerAboard(boolean aboard) {
        placement.setAboard(aboard);
        place();
    }

    private void place() {
        double[] at = placement.relative();
        this.x = at[0];
        this.y = at[1];
        this.z = at[2];
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

    /**
     * Scales this voice's level, for swelling the tonal layer under acceleration. The first
     * call snaps rather than glides, as with frequency: gliding down from full level would
     * sound every voice at once while the bank fades in, even voices meant to be silent,
     * and their decay together is heard as a bell.
     */
    public void setLoadScale(float scale) {
        this.targetLoadScale = scale;
        if (!loadInitialised) {
            this.loadScale = scale;
            this.loadInitialised = true;
        }
    }

    /**
     * Jumps straight to the pending frequency instead of gliding to it. Used at gear
     * changes, where a glide turns the step into part of one continuous rise.
     */
    public void snapToTarget() {
        this.pitch = this.targetPitch;
    }

    public void requestStop() {
        this.active = false;
    }

    /**
     * Every voice starts at zero volume so it can fade in, and the sound engine drops
     * sounds whose volume is zero at play() time unless they declare this. Without it the
     * whole bank is skipped before it ever reaches a channel, and the fade never runs.
     */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        this.pitch += (this.targetPitch - this.pitch) * PITCH_SMOOTHING;
        float loadCoefficient = this.targetLoadScale < this.loadScale ? LOAD_FALL : LOAD_RISE;
        this.loadScale += (this.targetLoadScale - this.loadScale) * loadCoefficient;
        this.fade = active
            ? Math.min(1f, this.fade + FADE_STEP)
            : Math.max(0f, this.fade - FADE_STEP);
        this.volume = baseVolume * this.fade * this.loadScale;
        placement.tick();
        place();

        if (!active && this.fade <= 0f) {
            this.stop();
        }
    }
}
