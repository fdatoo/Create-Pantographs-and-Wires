package de.mrjulsen.paw.mixin;

import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.mrjulsen.paw.event.ChunkLoadingEvents;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    @Shadow
    private ServerLevel level;

    /**
     * Wires for a chunk are sent when the chunk itself is sent to the player.
     *
     * This used to hook updateChunkTracking, but Lithium overwrites ChunkMap.move with its
     * own loop that calls playerLoadedChunk and ServerPlayer.untrackChunk directly. With
     * Lithium installed, updateChunkTracking only ran when a player joined or left, so wires
     * were sent for the chunks around the join point and never for anywhere the player went
     * afterwards. Every path that sends a chunk, vanilla or Lithium, goes through
     * playerLoadedChunk, and it also covers chunks that only become ready after the player
     * started watching them. The matching unwatch lives in ServerPlayerMixin.
     */
    @Inject(method = "playerLoadedChunk", at = @At("HEAD"))
    private void paw$onChunkSent(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk, CallbackInfo ci) {
        // Queued, never inline: see ChunkLoadingEvents.queueChunkWatch for the deadlock.
        ChunkLoadingEvents.queueChunkWatch(true, player, chunk.getPos(), level);
    }
}
