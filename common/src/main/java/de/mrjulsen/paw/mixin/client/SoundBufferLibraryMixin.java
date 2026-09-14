package de.mrjulsen.paw.mixin.client;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import de.mrjulsen.paw.client.sound.synth.SynthStreams;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;

/** Lets a synthesised sound supply its own audio stream instead of a decoded file (see SynthStreams). */
@Mixin(SoundBufferLibrary.class)
public class SoundBufferLibraryMixin {

    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void paw$provideSynthStream(ResourceLocation location, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        AudioStream stream = SynthStreams.claim(location);
        if (stream != null) {
            cir.setReturnValue(CompletableFuture.completedFuture(stream));
        }
    }
}
