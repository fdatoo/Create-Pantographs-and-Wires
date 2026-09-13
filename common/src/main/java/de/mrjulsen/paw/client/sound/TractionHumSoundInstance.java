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
 * Fades in on start and out on {@link #requestStop()}, and glides toward its
 * target frequency rather than jumping, so gear steps read as quick slides.
 */
@Environment(EnvType.CLIENT)
public class TractionHumSoundInstance extends AbstractTickableSoundInstance {

    private static final float MASTER_VOLUME = 0.6f;
    // 10 ticks (0.5s) to fade fully in or out.
    private static final float FADE_TICKS = 10f;
    // How quickly pitch glides toward its target each tick; smaller = slower spool-up.
    private static final float PITCH_SMOOTHING = 0.08f;
    // Minecraft's sound engine clamps playback pitch to this window, so every voice's
    // reference frequency is chosen to keep its working range inside it.
    private static final float MIN_ENGINE_PITCH = 0.5f;
    private static final float MAX_ENGINE_PITCH = 2.0f;

    private final float targetVolume;
    private final float fadeStep;
    private final double referenceFrequency;

    private boolean active = true;
    private float targetPitch = 1.0f;

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
        this.targetVolume = MASTER_VOLUME * level;
        this.fadeStep = this.targetVolume / FADE_TICKS;
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

    /** Drives this voice to an absolute frequency in Hz, clamped to what the engine can play. */
    public void setTargetFrequency(double frequencyHz) {
        float ratio = (float) (frequencyHz / referenceFrequency);
        this.targetPitch = Math.min(MAX_ENGINE_PITCH, Math.max(MIN_ENGINE_PITCH, ratio));
    }

    public void requestStop() {
        this.active = false;
    }

    @Override
    public void tick() {
        this.pitch += (this.targetPitch - this.pitch) * PITCH_SMOOTHING;

        if (active) {
            this.volume = Math.min(targetVolume, this.volume + fadeStep);
            return;
        }
        this.volume = Math.max(0f, this.volume - fadeStep);
        if (this.volume <= 0f) {
            this.stop();
        }
    }
}
