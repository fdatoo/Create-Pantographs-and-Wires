package de.mrjulsen.paw.traction;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.simibubi.create.foundation.utility.WorldAttached;

import de.mrjulsen.paw.blockentity.ThirdRailBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;

/**
 * The third rail blocks loaded in each client world, filed under every chunk their conductors reach,
 * so a collector shoe only looks at rails near it. Rail block entities file themselves when they
 * first tick and whenever their rails change, and leave when removed or unloaded.
 */
public final class ThirdRailIndex {
    private static final WorldAttached<Long2ObjectOpenHashMap<Set<ThirdRailBlockEntity>>> BY_CHUNK =
        new WorldAttached<>(level -> new Long2ObjectOpenHashMap<>());
    private static final WorldAttached<Map<ThirdRailBlockEntity, long[]>> CHUNKS_OF =
        new WorldAttached<>(level -> new Reference2ObjectOpenHashMap<>());

    private ThirdRailIndex() {}

    /** Files a rail under the chunks its conductors currently reach, replacing any earlier filing. */
    public static void update(LevelAccessor level, ThirdRailBlockEntity rail) {
        remove(level, rail);
        long[] chunks = chunksCovering(rail.conductorExtent());
        Long2ObjectOpenHashMap<Set<ThirdRailBlockEntity>> byChunk = BY_CHUNK.get(level);
        for (long chunk : chunks) {
            byChunk.computeIfAbsent(chunk, key -> new ReferenceOpenHashSet<>()).add(rail);
        }
        CHUNKS_OF.get(level).put(rail, chunks);
    }

    public static void remove(LevelAccessor level, ThirdRailBlockEntity rail) {
        long[] chunks = CHUNKS_OF.get(level).remove(rail);
        if (chunks == null) {
            return;
        }
        Long2ObjectOpenHashMap<Set<ThirdRailBlockEntity>> byChunk = BY_CHUNK.get(level);
        for (long chunk : chunks) {
            Set<ThirdRailBlockEntity> rails = byChunk.get(chunk);
            if (rails != null && rails.remove(rail) && rails.isEmpty()) {
                byChunk.remove(chunk);
            }
        }
    }

    /**
     * Whether any rail filed in the chunks the query box touches satisfies the test. A rail spanning
     * several of those chunks may be tested more than once.
     */
    public static boolean anyNear(LevelAccessor level, AABB query, Predicate<ThirdRailBlockEntity> test) {
        Long2ObjectOpenHashMap<Set<ThirdRailBlockEntity>> byChunk = BY_CHUNK.get(level);
        if (byChunk.isEmpty()) {
            return false;
        }
        int minX = SectionPos.blockToSectionCoord(query.minX);
        int maxX = SectionPos.blockToSectionCoord(query.maxX);
        int minZ = SectionPos.blockToSectionCoord(query.minZ);
        int maxZ = SectionPos.blockToSectionCoord(query.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Set<ThirdRailBlockEntity> rails = byChunk.get(ChunkPos.asLong(x, z));
                if (rails == null) {
                    continue;
                }
                for (ThirdRailBlockEntity rail : rails) {
                    if (test.test(rail)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static long[] chunksCovering(AABB box) {
        int minX = SectionPos.blockToSectionCoord(box.minX);
        int maxX = SectionPos.blockToSectionCoord(box.maxX);
        int minZ = SectionPos.blockToSectionCoord(box.minZ);
        int maxZ = SectionPos.blockToSectionCoord(box.maxZ);
        long[] chunks = new long[(maxX - minX + 1) * (maxZ - minZ + 1)];
        int i = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                chunks[i++] = ChunkPos.asLong(x, z);
            }
        }
        return chunks;
    }
}
