package de.mrjulsen.wires;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import de.mrjulsen.wires.network.WireChunkLoadingData;
import de.mrjulsen.wires.network.WiresNetworkSyncData.WireSyncDataEntry;
import de.mrjulsen.wires.render.WireSegmentRenderDataBatch;
import de.mrjulsen.wires.WireCollision.WireBlockCollision;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public final class WireClientNetwork implements IWireNetwork {

    private static ResourceLocation dimension;
    private static WireClientNetwork currentNetwork;
    private final Level level;

    protected WireClientNetwork(Level level) {
        this.level = level;
    }
    
    private final Multimap<ChunkPos, WireCollision> collisionByChunk = MultimapBuilder.hashKeys().hashSetValues().build();
    private final Multimap<SectionPos, WireCollision> collisionBySection = MultimapBuilder.hashKeys().hashSetValues().build();
    private final Multimap<BlockPos, WireCollision> collisionByBlock = MultimapBuilder.hashKeys().hashSetValues().build();
    
    private final Multimap<UUID, WireSegmentRenderDataBatch> renderDataById = MultimapBuilder.hashKeys().hashSetValues().build();
    private final Multimap<ChunkPos, WireSegmentRenderDataBatch> renderDataByChunk = MultimapBuilder.hashKeys().hashSetValues().build();
    private final Multimap<SectionPos, WireSegmentRenderDataBatch> renderDataBySection = MultimapBuilder.hashKeys().hashSetValues().build();

    public String debug_text() {
        return String.format("Wires[C]: Col: [%s,%s,%s], R: [%s,%s,%s]",
            collisionByChunk.size(),
            collisionBySection.size(),
            collisionByBlock.size(),

            renderDataById.size(),
            renderDataBySection.size(),
            renderDataByChunk.size()
        );
    }

    @Override
    public Level level() {
        return level;
    }

    public static synchronized void clear() {
        dimension = null;
        currentNetwork = null;
    }

    /**
     * Reached from both the render thread and the network thread, so it has to be
     * synchronized: without it, two threads can each see a null network, each build one,
     * and one overwrite the other, stranding every wire that synced into the loser. The
     * window is guaranteed rather than theoretical, since clear() nulls this on quit and
     * the bulk wire sync arrives immediately after the next join.
     *
     * The dimension test also compared ResourceLocation by reference. Equal locations from
     * different instances would have silently discarded the whole network and every wire in
     * it, so it compares by value now.
     */
    public static synchronized WireClientNetwork get(Level level) {
        ResourceLocation current = level.dimensionTypeId().location();
        if (currentNetwork == null || !current.equals(dimension)) {
            dimension = current;
            currentNetwork = new WireClientNetwork(level);
        }
        return currentNetwork;
    }



    // Every read below hands back a copy rather than the multimap's live view. Callers
    // iterate these while the network thread is mutating the same maps, and a live view
    // would throw ConcurrentModificationException mid-iteration.

    public synchronized Collection<WireCollision> getCollisionsTroughBlock(BlockPos pos) {
        return List.copyOf(collisionByBlock.get(pos));
    }

    public synchronized Collection<WireCollision> getCollisionsTroughSection(SectionPos pos) {
        return List.copyOf(collisionBySection.get(pos));
    }

    public synchronized Collection<WireCollision> getCollisionsTroughChunk(ChunkPos pos) {
        return List.copyOf(collisionByChunk.get(pos));
    }

    public synchronized Collection<WireBlockCollision> getCollisionsInBlock(BlockPos pos) {
        Collection<WireBlockCollision> connections = new LinkedList<>();
        for (WireCollision c : collisionByBlock.get(pos)) {
            connections.addAll(c.collisionsInBlock(pos));
        }
        return connections;
    }

    public synchronized boolean hasConnectionsInSection(SectionPos section) {
        return renderDataBySection.containsKey(section);
    }

    public synchronized boolean hasConnectionsInBlock(BlockPos pos) {
        return collisionByBlock.containsKey(pos);
    }

    /**
     * A copy, not the multimap's live view. This is iterated on the render thread while
     * a chunk section compiles, and the network thread mutates the same map as wire sync
     * arrives; handing out the view let that iteration throw partway through, which would
     * abort the compile and leave that one section's wires missing while others rendered.
     */
    public synchronized Collection<WireSegmentRenderDataBatch> connectionsInSection(SectionPos section) {
        return List.copyOf(renderDataBySection.get(section));
    }

    // Mutators run on the network thread while the render thread reads. They share the
    // readers' monitor so writes are not lost or seen half-applied.
    public synchronized void createClientConnection(@Nullable ChunkPos chunk, WireSyncDataEntry in) {
        try {
            createClientConnectionUnguarded(chunk, in);
        } catch (Exception e) {
            // Nothing guarded this before. A connection that throws here gets no render
            // data while the server still has it, so the wire is invisible but solid, and
            // refuses to be replaced because the server says one is already there. It also
            // fails identically on every rejoin, since the same data rebuilds the same way.
            // Whatever else goes wrong, it should not do so silently.
            WiresApi.LOGGER.error(
                "Failed to build client wire {} ({}) from {} to {}; it will not render.",
                in.data().getConnectionId(),
                in.data().getWireType(),
                in.data().getStartPos(),
                in.data().getEndPos(),
                e
            );
        }
    }

    private void createClientConnectionUnguarded(@Nullable ChunkPos chunk, WireSyncDataEntry in) {
        if (in.forceUpdate()) {
            removeClientConnection(in.data().getConnectionId());
        } else if (renderDataById.containsKey(in.data().getConnectionId())) {
            // A connection the client already knows, re-sent because its chunk came back
            // into view. Vanilla rebuilds a reloaded chunk's render sections from scratch,
            // and wires are baked into that geometry when the section compiles, so the
            // wire is gone from the section even though every map here still lists it.
            //
            // This branch used to flip the unloaded flag and return without marking
            // anything dirty, so nothing ever rebaked the wire: it stayed invisible for
            // the rest of the session while still being solid and still counting as
            // present, which is why re-wiring answered "already a wire there". Only a
            // relog cleared the network and took the full build path below.
            Collection<WireSegmentRenderDataBatch> known = renderDataById.get(in.data().getConnectionId());
            for (WireSegmentRenderDataBatch renderdata : known) {
                if (chunk == null || chunk.equals(renderdata.getSection().chunk())) {
                    renderdata.setUnloaded(false);
                }
            }
            // Every section the wire occupies, not just the ones in the chunk that
            // triggered this: a wire crossing a chunk border is rebuilt section by
            // section, and the neighbours lose their half of it just the same.
            for (WireSegmentRenderDataBatch renderdata : known) {
                setSectionDirty(renderdata.getSection());
            }
            return;
        }
        
        Set<SectionPos> sectionsIn = new HashSet<>();
        IWireType renderer = WireTypeRegistry.get(in.data().getWireType());

        WireBatch batch = renderer.buildWire(WireCreationContext.BOTH, Minecraft.getInstance().level, in.data());
        batch.splitRenderDataInChunkSections(in.data().getConnectionId(), in.data().getOriginChunkSection()).entrySet().forEach(x -> {
            renderDataByChunk.put(x.getKey().chunk(), x.getValue());
            renderDataBySection.put(x.getKey(), x.getValue());  
            renderDataById.put(in.data().getConnectionId(), x.getValue());
            sectionsIn.add(x.getKey());
        });

        new WireCollision(collisionByChunk, collisionBySection, collisionByBlock, in.data().getConnectionId(), in.data().getStartBlockPos(), batch.getCollisions());

        for (SectionPos section : sectionsIn) {
            setSectionDirty(section);
        }
    }

    public synchronized void removeClientConnections(UUID[] connectionIds) {
        for (UUID id : connectionIds) {
            removeClientConnection(id);
        }
    }

    public synchronized void removeClientConnection(UUID connectionId) {
        if (!renderDataById.containsKey(connectionId)) {
            return;
        }

        Collection<WireSegmentRenderDataBatch> renderdata = renderDataById.removeAll(connectionId);
        renderDataBySection.values().removeAll(renderdata);
        renderDataByChunk.values().removeAll(renderdata);
        collisionByBlock.values().removeIf(x -> x.getId().equals(connectionId));
        collisionByChunk.values().removeIf(x -> x.getId().equals(connectionId));
        collisionBySection.values().removeIf(x -> x.getId().equals(connectionId));
        
        for (WireSegmentRenderDataBatch batch : renderdata) {
            SectionPos section = batch.getSection();
            setSectionDirty(section);
        }
    }

    public synchronized void onClientChunkLoading(WireChunkLoadingData in) {
        synchronized (renderDataByChunk) {
            Set<UUID> emptyConnections = new HashSet<>();
            for (WireSegmentRenderDataBatch renderdata : renderDataByChunk.get(in.pos())) {
                renderdata.setUnloaded(!in.load());
                if (!renderDataById.containsKey(renderdata.getId()) || renderDataById.get(renderdata.getId()).stream().allMatch(WireSegmentRenderDataBatch::isUnloaded)) {
                    emptyConnections.add(renderdata.getId());
                }
            }

            for (UUID id : emptyConnections) {                
                removeClientConnection(id);
            }
        }
    }

    private void setSectionDirty(SectionPos pos) {
        Minecraft.getInstance().execute(() -> {            
            Minecraft.getInstance().levelRenderer.setSectionDirty(pos.getX(), pos.getY(), pos.getZ());
        });
    }
   
}
