package de.mrjulsen.paw.traction;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Fills the blocks under a newly laid third rail with the block held in the off hand, as Create's track
 * placement paves under track: only free spaces (air, plants, fluids) are filled, slabs go in as double
 * slabs, and blocks with block entities or no collision can't be used.
 */
public final class ThirdRailPaver {
    /** Distance between the points sampled along the rail, fine enough to catch every block a curve crosses. */
    private static final double STEP = 0.25;

    private ThirdRailPaver() {}

    /** The blocks directly under a laid rail, from end to end, each once. */
    public static Set<BlockPos> positions(ThirdRailConnection connection) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        positions.add(connection.owner().below());
        ThirdRailCurve curve = connection.curve();
        double length = curve.length();
        int steps = Math.max(1, (int) Math.ceil(length / STEP));
        Vector3d point = new Vector3d();
        for (int i = 0; i <= steps; i++) {
            curve.position(curve.parameterAtDistance(length * i / steps), point);
            // The rail runs along the bottom of its blocks, so half a block down is inside the block beneath.
            positions.add(BlockPos.containing(point.x, point.y - 0.5, point.z));
        }
        positions.add(connection.other().below());
        return positions;
    }

    /** Whether a block can pave: a solid block without a block entity. */
    public static boolean canPaveWith(Level level, @Nullable Block block, BlockPos at) {
        return block != null && !(block instanceof EntityBlock)
            && !block.defaultBlockState().getCollisionShape(level, at).isEmpty();
    }

    /**
     * Places the block in every free position, or with simulate only counts them.
     *
     * @return items used: one per block, two per double slab
     */
    public static int pave(Level level, Collection<BlockPos> positions, Block block, boolean simulate) {
        BlockState state = block.defaultBlockState();
        boolean slab = state.hasProperty(SlabBlock.TYPE);
        if (slab) {
            state = state.setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        }
        int items = 0;
        for (BlockPos pos : positions) {
            BlockState existing = level.getBlockState(pos);
            if (existing.getBlock() == state.getBlock() || !existing.canBeReplaced()) {
                continue;
            }
            if (!simulate) {
                level.setBlock(pos, state, Block.UPDATE_ALL);
            }
            items += slab ? 2 : 1;
        }
        return items;
    }
}
