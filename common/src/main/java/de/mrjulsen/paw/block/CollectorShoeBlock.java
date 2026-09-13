package de.mrjulsen.paw.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.utility.VoxelShaper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The third rail equivalent of a pantograph. Mounted on the side of a train, it faces outward and
 * picks up a third rail on that side only; see CollectorShoeMovementBehaviour.
 */
public class CollectorShoeBlock extends Block implements IWrenchable {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /** The mount, arm and insulator, all inside the shoe's own block. */
    private static final VoxelShaper MOUNT = VoxelShaper.forHorizontal(Shapes.or(
        Block.box(3, 3, 14, 13, 14, 16),
        Block.box(5.5, 6.5, 0, 10.5, 13.5, 14)
    ), Direction.NORTH);

    /**
     * The whole shoe, including the hanger and shoe that reach out and down into the block where a
     * third rail runs, to sit between its conductor and cover board.
     */
    private static final VoxelShaper OUTLINE = VoxelShaper.forHorizontal(Shapes.or(
        Block.box(3, 3, 14, 13, 14, 16),
        Block.box(5.5, 6.5, -1, 10.5, 13.5, 14),
        Block.box(7, -8.5, -3, 9, 10.5, -1),
        Block.box(3, -10, -11, 13, -8.5, -1)
    ), Direction.NORTH);

    public CollectorShoeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    /** Faces away from the block it was placed against, or away from the player when placed on a floor. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        Direction facing = face.getAxis().isHorizontal() ? face : context.getHorizontalDirection();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE.get(state.getValue(FACING));
    }

    /** Only the part inside the block collides, so the hanging shoe never snags a platform edge or the rail. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return MOUNT.get(state.getValue(FACING));
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
