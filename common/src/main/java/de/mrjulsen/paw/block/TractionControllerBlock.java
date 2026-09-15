package de.mrjulsen.paw.block;

import com.simibubi.create.foundation.block.IBE;

import de.mrjulsen.paw.blockentity.TractionControllerBlockEntity;
import de.mrjulsen.paw.event.ClientWrapper;
import de.mrjulsen.paw.network.TrainSettingsPacket;
import de.mrjulsen.paw.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A train's settings panel. Place it anywhere on a train and right-click it, before assembly or while
 * the train runs, to choose the train's sound pack, speedometer unit and steam sound.
 */
public class TractionControllerBlock extends HorizontalDirectionalBlock implements IBE<TractionControllerBlockEntity> {
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 10, 15);

    public TractionControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide && level.getBlockEntity(pos) instanceof TractionControllerBlockEntity controller) {
            ClientWrapper.showTractionControllerScreen(TrainSettingsPacket.Target.block(pos), controller.settings());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    public Class<TractionControllerBlockEntity> getBlockEntityClass() {
        return TractionControllerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TractionControllerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.TRACTION_CONTROLLER_BLOCK_ENTITY.get();
    }
}
