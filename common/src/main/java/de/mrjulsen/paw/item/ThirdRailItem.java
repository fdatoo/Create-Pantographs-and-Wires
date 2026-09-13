package de.mrjulsen.paw.item;

import javax.annotation.Nullable;

import com.simibubi.create.AllSoundEvents;

import de.mrjulsen.paw.block.ThirdRailBlock;
import de.mrjulsen.paw.traction.ThirdRailPlacement;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places third rail the way Create's track item places track. Without a selection it places a single
 * rail block, or selects a clicked rail block as the start of a new rail. With a selection, clicking
 * a second point lays a straight or curved rail to it, and sneak-clicking clears the selection.
 */
public class ThirdRailItem extends BlockItem {

    public ThirdRailItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || context.getHand() == InteractionHand.OFF_HAND) {
            return super.useOn(context);
        }
        ItemStack stack = context.getItemInHand();
        Level level = context.getLevel();
        BlockState clicked = level.getBlockState(context.getClickedPos());

        if (!ThirdRailPlacement.hasSelection(stack)) {
            if (clicked.getBlock() instanceof ThirdRailBlock) {
                ThirdRailPlacement.select(stack, context.getClickedPos(), clicked.getValue(ThirdRailBlock.SHAPE), player.getLookAngle());
                level.playSound(player, context.getClickedPos(), SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.75f, 1);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            return super.useOn(context);
        }

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("create.track.selection_cleared"), true);
                ThirdRailPlacement.clearSelection(stack);
            } else {
                level.playSound(player, context.getClickedPos(), SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.75f, 1);
            }
            return InteractionResult.SUCCESS;
        }

        ThirdRailPlacement.Target target = ThirdRailPlacement.target(level, new BlockPlaceContext(context), context.getClickedPos(), this);
        if (target == null) {
            return InteractionResult.FAIL;
        }
        ThirdRailPlacement.Attempt attempt = ThirdRailPlacement.tryConnect(level, player, stack, target);
        if (attempt.message() != null && !level.isClientSide) {
            player.displayClientMessage(attempt.message(), true);
        }
        if (!attempt.valid()) {
            AllSoundEvents.DENY.playFrom(player, 1, 1);
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide) {
            ThirdRailPlacement.commit(level, player, context.getHand(), target, attempt);
        }
        return InteractionResult.SUCCESS;
    }

    /** The rail block this item would place for a click, for picking the far end of a new rail. */
    @Nullable
    public BlockState placementStateFor(BlockPlaceContext context) {
        return getPlacementState(context);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return ThirdRailPlacement.hasSelection(stack);
    }
}
