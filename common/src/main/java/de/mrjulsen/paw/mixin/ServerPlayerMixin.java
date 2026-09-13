package de.mrjulsen.paw.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.mrjulsen.paw.event.ChunkLoadingEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    /**
     * The unwatch half of ChunkMapMixin. Vanilla's updateChunkTracking and Lithium's
     * replacement for ChunkMap.move both end up here when a player stops watching a chunk,
     * so hooking this keeps the two in step whichever path is active.
     */
    @Inject(method = "untrackChunk", at = @At("HEAD"))
    private void paw$onChunkForgotten(ChunkPos chunkPos, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer)(Object)this;
        ChunkLoadingEvents.fireChunkWatch(false, self, chunkPos, self.serverLevel());
    }
}
