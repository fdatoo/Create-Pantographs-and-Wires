package de.mrjulsen.paw.client.sound.synth;

import javax.annotation.Nullable;

import de.mrjulsen.paw.PantographsAndWires;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.resources.ResourceLocation;

/**
 * Hands a generated stream to Minecraft when a synthesised sound starts. The sound event points at a
 * placeholder file marked for streaming; SoundBufferLibraryMixin intercepts the request to open it
 * and returns the stream offered here instead. Minecraft opens the stream synchronously inside play,
 * on the client thread, so offering it just before and clearing it just after is enough.
 */
@Environment(EnvType.CLIENT)
public final class SynthStreams {
    /** The placeholder's path as the sound engine asks for it. */
    public static final ResourceLocation PLACEHOLDER = new ResourceLocation(PantographsAndWires.MOD_ID, "sounds/electric/synth.ogg");

    @Nullable
    private static AudioStream pending;

    private SynthStreams() {}

    /** Plays the instance with the given stream as its audio. */
    public static void play(SoundInstance instance, AudioStream stream) {
        pending = stream;
        try {
            Minecraft.getInstance().getSoundManager().play(instance);
        } finally {
            pending = null;
        }
    }

    /** The stream to use for a request to open this path, if one is waiting. */
    @Nullable
    public static AudioStream claim(ResourceLocation path) {
        if (pending == null || !PLACEHOLDER.equals(path)) {
            return null;
        }
        AudioStream stream = pending;
        pending = null;
        return stream;
    }
}
