package de.mrjulsen.paw.event;

import de.mrjulsen.wires.WireNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public class ChunkLoadingEvents {
    public static void fireChunkWatch(boolean watch, ServerPlayer entity, ChunkPos chunkpos, ServerLevel level) {
        if (watch) onChunkWatch(level, chunkpos, entity);
        else onChunkUnWatch(level, chunkpos, entity);
    }

    /**
     * Queue a watch change for the server's next task poll instead of running it inline.
     *
     * The chunk hooks fire from inside chunk sending, while the chunk being sent can still
     * be mid-promotion. onChunkLoad reads block states at wire endpoints, and reading one in
     * such a chunk parks the server thread waiting on a future only that thread can
     * complete, until the watchdog kills the server. tell() always queues, unlike execute(),
     * which runs inline when already on the server thread. Loads and unloads share the one
     * queue, so their order is kept.
     */
    public static void queueChunkWatch(boolean watch, ServerPlayer entity, ChunkPos chunkpos, ServerLevel level) {
        MinecraftServer server = level.getServer();
        server.tell(new TickTask(server.getTickCount(), () -> fireChunkWatch(watch, entity, chunkpos, level)));
    }

    public static void fireChunkWatch(boolean wasLoaded, boolean load, ServerPlayer entity, ChunkPos chunkpos, ServerLevel level) {
        if (wasLoaded != load) fireChunkWatch(load, entity, chunkpos, level);
    }


    public static void onChunkWatch(Level level, ChunkPos pos, Player player) {
		  WireNetwork.get(level).onChunkLoad(level, pos, player);
    }

    public static void onChunkUnWatch(Level level, ChunkPos pos, Player player) {
		  WireNetwork.get(level).onChunkUnload(level, pos, player);
    }
}
