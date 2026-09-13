package de.mrjulsen.paw.mixin;

import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.mrjulsen.paw.event.ChunkLoadingEvents;
import de.mrjulsen.wires.WireNetwork;
import de.mrjulsen.wires.WiresApi;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    @Shadow
    private ServerLevel level;

    @Inject(method = "updateChunkTracking", at = @At(value = "HEAD"))
    protected void updateChunkTracking(ServerPlayer player, ChunkPos chunkPos, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, boolean wasLoaded, boolean load, CallbackInfo ci) {
        // [PaW track] diagnostics: wires sync only at join and leave, never while moving.
        // Log every real transition with the network it consults, to tell "this hook does
        // not fire on movement" apart from "it fires and onChunkLoad finds nothing".
        if (wasLoaded != load) {
            WireNetwork net = WireNetwork.get(level);
            WiresApi.LOGGER.info("[PaW track] {} {} for {} | net@{} {}", load ? "watch" : "unwatch", chunkPos,
                player.getGameProfile().getName(), Integer.toHexString(System.identityHashCode(net)), net.debug_text());
        }
        ChunkLoadingEvents.fireChunkWatch(wasLoaded, load, player, chunkPos, level);
    }
}
