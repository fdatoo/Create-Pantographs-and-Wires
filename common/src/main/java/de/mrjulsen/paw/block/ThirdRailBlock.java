package de.mrjulsen.paw.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import de.mrjulsen.paw.block.property.EThirdRailShape;
import de.mrjulsen.paw.blockentity.ThirdRailBlockEntity;
import de.mrjulsen.paw.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One end of a third rail, or a single rail block on its own. The rail's geometry lives in
 * ThirdRailBlockEntity and is drawn by its renderer; the block has no collision, so nothing stops an
 * entity from walking into the conductor.
 */
public class ThirdRailBlock extends Block implements IBE<ThirdRailBlockEntity>, IWrenchable {

    public static final EnumProperty<EThirdRailShape> SHAPE = EnumProperty.create("shape", EThirdRailShape.class);

    private static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, 11, 16);

    public ThirdRailBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SHAPE, EThirdRailShape.Z));
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SHAPE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Player player = context.getPlayer();
        Vec3 look = player != null ? player.getLookAngle() : Vec3.atLowerCornerOf(context.getHorizontalDirection().getNormal());
        return defaultBlockState().setValue(SHAPE, EThirdRailShape.fromLook(look));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(SHAPE, state.getValue(SHAPE).rotate(rotation));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(SHAPE, state.getValue(SHAPE).mirror(mirror));
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (player.isCreative() && level.getBlockEntity(pos) instanceof ThirdRailBlockEntity rail) {
            rail.setCancelDrops(true);
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof ThirdRailBlockEntity rail) {
            rail.removeInboundConnections(true);
        }
        IBE.onRemove(state, level, pos, newState);
    }

    /** A wrench moves the cover's supports to the other side of the rail instead of rotating the block. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (!(level.getBlockEntity(context.getClickedPos()) instanceof ThirdRailBlockEntity rail)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            rail.flipSupports();
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(Component.translatable("pantographsandwires.third_rail.supports_moved"), true);
            }
        }
        playRotateSound(level, context.getClickedPos());
        return InteractionResult.SUCCESS;
    }

    @Override
    public Class<ThirdRailBlockEntity> getBlockEntityClass() {
        return ThirdRailBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ThirdRailBlockEntity> getBlockEntityType() {
        return ModBlockEntities.THIRD_RAIL_BLOCK_ENTITY.get();
    }
}
