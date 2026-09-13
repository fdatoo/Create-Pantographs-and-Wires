package de.mrjulsen.paw.traction;

import javax.annotation.Nullable;

import org.joml.Vector3d;

import de.mrjulsen.paw.block.ThirdRailBlock;
import de.mrjulsen.paw.block.property.EThirdRailShape;
import de.mrjulsen.paw.blockentity.ThirdRailBlockEntity;
import de.mrjulsen.paw.config.ModServerConfig;
import de.mrjulsen.paw.item.ThirdRailItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Laying a third rail between two points, the way Create lays track: click a rail block to select
 * it, then click a second point (an existing rail block, or anywhere a rail block could go) to lay a
 * straight or curved rail to it. Shared by the item on both sides and the client preview.
 */
public final class ThirdRailPlacement {
    private static final String NBT_SELECTION = "ConnectingFrom";
    private static final String NBT_POS = "Pos";
    private static final String NBT_AXIS = "Axis";

    /** Where the second click lands: an existing rail block, or a new one to place. */
    public record Target(BlockPos pos, EThirdRailShape shape, boolean existing, @Nullable BlockState placementState) {}

    /**
     * @param connection the rail from the selected block to the target, for previewing, or null when
     *                   the problem leaves nothing sensible to draw
     * @param railsNeeded rails used up, including the new end block when one is placed
     */
    public record Attempt(boolean valid, @Nullable Component message, @Nullable ThirdRailConnection connection, int railsNeeded) {}

    private ThirdRailPlacement() {}

    public static boolean hasSelection(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(NBT_SELECTION);
    }

    public static void select(ItemStack stack, BlockPos pos, EThirdRailShape shape, Vec3 look) {
        Vector3d axis = ThirdRailPlacementRules.alongLook(joml(shape.axis()), joml(look));
        CompoundTag selection = stack.getOrCreateTagElement(NBT_SELECTION);
        selection.put(NBT_POS, NbtUtils.writeBlockPos(pos));
        ListTag axisTag = new ListTag();
        axisTag.add(DoubleTag.valueOf(axis.x));
        axisTag.add(DoubleTag.valueOf(axis.y));
        axisTag.add(DoubleTag.valueOf(axis.z));
        selection.put(NBT_AXIS, axisTag);
    }

    public static void clearSelection(ItemStack stack) {
        if (stack.hasTag()) {
            stack.removeTagKey(NBT_SELECTION);
        }
    }

    @Nullable
    public static Target target(Level level, BlockPlaceContext place, BlockPos clicked, ThirdRailItem item) {
        BlockState clickedState = level.getBlockState(clicked);
        if (clickedState.getBlock() instanceof ThirdRailBlock) {
            return new Target(clicked, clickedState.getValue(ThirdRailBlock.SHAPE), true, null);
        }
        if (!place.canPlace()) {
            return null;
        }
        BlockPos pos = place.getClickedPos();
        BlockState existing = level.getBlockState(pos);
        if (existing.getBlock() instanceof ThirdRailBlock) {
            return new Target(pos, existing.getValue(ThirdRailBlock.SHAPE), true, null);
        }
        BlockState state = item.placementStateFor(place);
        if (state == null || !state.hasProperty(ThirdRailBlock.SHAPE)) {
            return null;
        }
        return new Target(pos, state.getValue(ThirdRailBlock.SHAPE), false, state);
    }

    public static Attempt tryConnect(Level level, Player player, ItemStack stack, Target target) {
        CompoundTag selection = stack.getTag().getCompound(NBT_SELECTION);
        BlockPos pos1 = NbtUtils.readBlockPos(selection.getCompound(NBT_POS));
        ListTag axisTag = selection.getList(NBT_AXIS, Tag.TAG_DOUBLE);
        Vector3d axis1 = new Vector3d(axisTag.getDouble(0), axisTag.getDouble(1), axisTag.getDouble(2));

        if (!(level.getBlockState(pos1).getBlock() instanceof ThirdRailBlock)) {
            return new Attempt(false, Component.translatable("create.track.original_missing").withStyle(ChatFormatting.RED), null, 0);
        }

        Vector3d axis2 = ThirdRailPlacementRules.alongLook(joml(target.shape().axis()), joml(player.getLookAngle()));
        BlockPos pos2 = target.pos();
        ThirdRailPlacementRules.Outcome outcome = ThirdRailPlacementRules.evaluate(
            pos1.getX(), pos1.getY(), pos1.getZ(), axis1,
            pos2.getX(), pos2.getY(), pos2.getZ(), axis2,
            maxLength()
        );

        ThirdRailConnection connection = null;
        if (outcome.hasCurve()) {
            connection = new ThirdRailConnection(pos1, pos2, vec(outcome.end1()), vec(outcome.axis1()), vec(outcome.end2()), vec(outcome.axis2()), true, true);
            connection = connection.withSupportsOnRight(supportsAwayFrom(connection, player.position()));
        }

        if (!outcome.valid()) {
            ChatFormatting colour = "second_point".equals(outcome.problem()) ? ChatFormatting.WHITE : ChatFormatting.RED;
            return new Attempt(false, Component.translatable("create.track." + outcome.problem()).withStyle(colour), connection, 0);
        }

        int needed = connection.railCost() + (target.existing() ? 0 : 1);
        if (!player.isCreative() && countItems(player.getInventory(), stack.getItem()) < needed) {
            return new Attempt(false, Component.translatable("pantographsandwires.third_rail.not_enough_rails").withStyle(ChatFormatting.RED), connection, needed);
        }
        return new Attempt(true, Component.translatable("create.track.valid_connection").withStyle(ChatFormatting.GREEN), connection, needed);
    }

    /** Places the end block if needed, lays the rail at both ends and uses up the rails. Server only. */
    public static void commit(Level level, Player player, InteractionHand hand, Target target, Attempt attempt) {
        ThirdRailConnection connection = attempt.connection();
        if (!target.existing()) {
            level.setBlock(target.pos(), target.placementState(), 3);
        }
        if (level.getBlockEntity(connection.owner()) instanceof ThirdRailBlockEntity first
            && level.getBlockEntity(connection.other()) instanceof ThirdRailBlockEntity second) {
            first.addConnection(connection);
            second.addConnection(connection.secondary());
        }

        ItemStack held = player.getItemInHand(hand);
        Item rail = held.getItem();
        clearSelection(held);
        if (!player.isCreative()) {
            removeItems(player.getInventory(), rail, attempt.railsNeeded());
        }

        BlockState placed = level.getBlockState(target.pos());
        SoundType sound = placed.getSoundType();
        level.playSound(null, target.pos(), sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
    }

    /**
     * Supports go on the side of the rail away from the player: whoever lays a rail beside the track
     * is usually standing on the track, and the shoe needs that side of the rail open.
     */
    private static boolean supportsAwayFrom(ThirdRailConnection connection, Vec3 player) {
        ThirdRailCurve curve = connection.curve();
        double t = curve.parameterAtDistance(curve.length() / 2);
        Vector3d mid = curve.position(t, new Vector3d());
        Vector3d right = curve.derivative(t, new Vector3d()).cross(0, 1, 0);
        double side = (player.x - mid.x) * right.x + (player.z - mid.z) * right.z;
        return side < 0;
    }

    /** The server's limit, or its default while a client has not received the server config yet. */
    private static int maxLength() {
        try {
            return ModServerConfig.THIRD_RAIL_MAX_LENGTH.get();
        } catch (IllegalStateException notLoaded) {
            return ModServerConfig.THIRD_RAIL_MAX_LENGTH.getDefault();
        }
    }

    private static int countItems(Inventory inventory, Item item) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removeItems(Inventory inventory, Item item, int amount) {
        int selected = inventory.selected;
        // Take from the held stack last, so the player keeps holding rails while they last.
        for (int pass = 0; pass < 2 && amount > 0; pass++) {
            for (int i = 0; i < inventory.getContainerSize() && amount > 0; i++) {
                if ((pass == 0) == (i == selected)) {
                    continue;
                }
                ItemStack stack = inventory.getItem(i);
                if (!stack.is(item)) {
                    continue;
                }
                int taken = Math.min(amount, stack.getCount());
                stack.shrink(taken);
                amount -= taken;
            }
        }
        inventory.setChanged();
    }

    private static Vector3d joml(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }

    private static Vec3 vec(Vector3d v) {
        return new Vec3(v.x, v.y, v.z);
    }
}
