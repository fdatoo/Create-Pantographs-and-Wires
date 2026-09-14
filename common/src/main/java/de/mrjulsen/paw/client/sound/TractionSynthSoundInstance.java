package de.mrjulsen.paw.client.sound;

import de.mrjulsen.paw.config.ModClientConfig;
import de.mrjulsen.paw.registry.ModSounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/**
 * The positional sound a synthesised traction voice plays through. Its audio comes from a live stream
 * (see SynthStreams); this only follows the train, sets the loudness and fades in and out.
 */
@Environment(EnvType.CLIENT)
public class TractionSynthSoundInstance extends AbstractTickableSoundInstance {
    private static final float FADE_STEP = 1f / 10f;

    private boolean active = true;
    private float fade;

    public TractionSynthSoundInstance(double x, double y, double z) {
        super(ModSounds.TRACTION_SYNTH.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.looping = false;
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

    public void requestStop() {
        this.active = false;
    }

    public boolean isStopping() {
        return !active;
    }

    /** Starts at zero volume to fade in; the engine would otherwise drop it before it reaches a channel. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        fade = active ? Math.min(1f, fade + FADE_STEP) : Math.max(0f, fade - FADE_STEP);
        volume = ModClientConfig.TRACTION_VOLUME.get().floatValue() * fade;
        if (!active && fade <= 0f) {
            stop();
        }
    }
}
