package de.mrjulsen.paw.traction;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import com.simibubi.create.foundation.utility.WorldAttached;

import de.mrjulsen.paw.blockentity.ThirdRailBlockEntity;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.level.LevelAccessor;

/**
 * The third rail blocks currently loaded in each client world, so collector shoes can find nearby
 * rails without scanning block entities chunk by chunk. Rail block entities register when they first
 * tick and leave when they are removed or their chunk unloads.
 */
public final class ThirdRailIndex {
    private static final WorldAttached<Set<ThirdRailBlockEntity>> RAILS = new WorldAttached<>(level -> new ReferenceOpenHashSet<>());

    private ThirdRailIndex() {}

    public static void add(LevelAccessor level, ThirdRailBlockEntity rail) {
        RAILS.get(level).add(rail);
    }

    public static void remove(LevelAccessor level, ThirdRailBlockEntity rail) {
        RAILS.get(level).remove(rail);
    }

    public static Collection<ThirdRailBlockEntity> rails(LevelAccessor level) {
        return Collections.unmodifiableSet(RAILS.get(level));
    }
}
