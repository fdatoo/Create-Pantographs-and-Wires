package de.mrjulsen.paw.client.sound;

import de.mrjulsen.paw.config.ModClientConfig;
import de.mrjulsen.paw.registry.ModSounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/**
 * The sound a streamed traction voice plays through. Its audio comes from a live stream (see
 * SynthStreams); this places it (see ListenerRelativePlacement), sets the loudness and fades in and out.
 */
@Environment(EnvType.CLIENT)
public class TractionSynthSoundInstance extends AbstractTickableSoundInstance {
    private final float fadeInStep;
    private final float fadeOutStep;
    private final ListenerRelativePlacement placement;

    private boolean active = true;
    private float fade;

    /**
     * @param fadeInTicks  ticks to fade in, or 0 to start at full volume
     * @param fadeOutTicks ticks to fade out; the stream's own envelopes shape everything finer
     */
    public TractionSynthSoundInstance(double x, double y, double z, int fadeInTicks, int fadeOutTicks) {
        super(ModSounds.TRACTION_SYNTH.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.fadeInStep = fadeInTicks <= 0 ? 1f : 1f / fadeInTicks;
        this.fadeOutStep = 1f / Math.max(1, fadeOutTicks);
        this.fade = fadeInTicks <= 0 ? 1f : 0f;
        this.placement = new ListenerRelativePlacement(x, y, z);
        this.looping = false;
        this.delay = 0;
        this.volume = ModClientConfig.TRACTION_VOLUME.get().floatValue() * fade;
        this.pitch = 1.0f;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.relative = true;
        place();
    }

    public void updatePosition(double x, double y, double z) {
        placement.setWorldPosition(x, y, z);
    }

    public void setListenerAboard(boolean aboard) {
        placement.setAboard(aboard);
        place();
    }

    public void requestStop() {
        this.active = false;
    }

    /** Starts at zero volume to fade in; the engine would otherwise drop it before it reaches a channel. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        fade = active ? Math.min(1f, fade + fadeInStep) : Math.max(0f, fade - fadeOutStep);
        volume = ModClientConfig.TRACTION_VOLUME.get().floatValue() * fade;
        placement.tick();
        place();
        if (!active && fade <= 0f) {
            stop();
        }
    }

    private void place() {
        double[] at = placement.relative();
        x = at[0];
        y = at[1];
        z = at[2];
    }
}
