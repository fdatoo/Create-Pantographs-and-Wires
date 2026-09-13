package de.mrjulsen.paw.client.sound;

import de.mrjulsen.paw.registry.ModSounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/**
 * A looping traction hum that follows a moving vehicle. Fades in on start and
 * out on {@link #requestStop()} so several pantographs handing off contact on
 * the same vehicle (via {@link TractionSoundManager}'s grace window) never
 * produces an audible click.
 */
@Environment(EnvType.CLIENT)
public class TractionHumSoundInstance extends AbstractTickableSoundInstance {

    private static final float TARGET_VOLUME = 0.6f;
    // 10 ticks (0.5s) to fade fully in or out.
    private static final float FADE_STEP = TARGET_VOLUME / 10f;

    private boolean active = true;

    public TractionHumSoundInstance(double x, double y, double z) {
        super(ModSounds.ELECTRIC_TRACTION_HUM.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
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

    public void requestStop() {
        this.active = false;
    }

    @Override
    public void tick() {
        if (active) {
            this.volume = Math.min(TARGET_VOLUME, this.volume + FADE_STEP);
            return;
        }
        this.volume = Math.max(0f, this.volume - FADE_STEP);
        if (this.volume <= 0f) {
            this.stop();
        }
    }
}
