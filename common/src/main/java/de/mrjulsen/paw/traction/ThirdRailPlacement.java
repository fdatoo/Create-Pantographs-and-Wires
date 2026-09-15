package de.mrjulsen.paw.traction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Vector3d;

import de.mrjulsen.paw.block.ThirdRailBlock;
import de.mrjulsen.paw.block.property.EThirdRailShape;
import de.mrjulsen.paw.blockentity.ThirdRailBlockEntity;
import de.mrjulsen.paw.config.ModServerConfig;
import de.mrjulsen.paw.item.ThirdRailItem;
import de.mrjulsen.paw.registry.ModBlocks;
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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Laying a third rail between two points, the way Create lays track: click a rail block to select
 * it, then click a second point (an existing rail block, or anywhere a rail block could go) to lay a
 * straight or curved rail to it. Curves are Create's compact size with straight rail blocks leading into
 * them, or with the sprint key held, as large as the room allows (ThirdRailPlacementRules). A solid block
 * held in the off hand fills the blocks under the new rail, as it paves under Create track. Shared by the
 * item on both sides and the client preview.
 */
public final class ThirdRailPlacement {
    private static final String NBT_SELECTION = "ConnectingFrom";
    private static final String NBT_POS = "Pos";
    private static final String NBT_AXIS = "Axis";
    /** Set by ThirdRailMaximisePacket just before the click that uses it, as Create does for track. */
    private static final String NBT_MAXIMISE = "MaximiseCurve";

    /** Where the second click lands: an existing rail block, or a new one to place. */
    public record Target(BlockPos pos, EThirdRailShape shape, boolean existing, @Nullable BlockState placementState) {}

    /**
     * A straight rail block leading into the curve.
     *
     * @param existing whether a matching rail block is already there
     */
    public record Extension(BlockPos pos, BlockState state, boolean existing) {}

    /**
     * @param connection     the rail from the selected block to the target, for previewing, or null when
     *                       the problem leaves nothing sensible to draw
     * @param railsNeeded    rails used up: the curve, and every new rail block
     * @param extensions     straight rail blocks leading into the curve
     * @param maximisable    whether holding the sprint key would lay a larger curve instead of straight rail
     * @param pavement       the off-hand block to pave under the rail with, or null for no paving
     * @param paveAt         where pavement goes
     * @param pavementNeeded blocks of pavement used up
     */
    public record Attempt(boolean valid, @Nullable Component message, @Nullable ThirdRailConnection connection, int railsNeeded,
        List<Extension> extensions, boolean maximisable, @Nullable Block pavement, Set<BlockPos> paveAt, int pavementNeeded) {

        static Attempt failed(Component message, @Nullable ThirdRailConnection connection) {
            return new Attempt(false, message, connection, 0, List.of(), false, null, Set.of(), 0);
        }
    }

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
            stack.removeTagKey(NBT_MAXIMISE);
        }
    }

    /** Server: the client says the sprint key is held for the click that follows. */
    public static void markMaximise(ItemStack stack) {
        if (hasSelection(stack)) {
            stack.getTag().putBoolean(NBT_MAXIMISE, true);
        }
    }

    /** Server: whether the click being handled was made with the sprint key held, clearing the mark. */
    public static boolean takeMaximise(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(NBT_MAXIMISE)) {
            return false;
        }
        boolean maximise = stack.getTag().getBoolean(NBT_MAXIMISE);
        stack.removeTagKey(NBT_MAXIMISE);
        return maximise;
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
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
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

    /** @param maximise whether the sprint key is held: the curve takes up all the room instead of Create's compact size */
    public static Attempt tryConnect(Level level, Player player, ItemStack stack, Target target, boolean maximise) {
        CompoundTag selection = stack.getTag().getCompound(NBT_SELECTION);
        BlockPos pos1 = NbtUtils.readBlockPos(selection.getCompound(NBT_POS));
        BlockState state1 = level.getBlockState(pos1);
        if (!(state1.getBlock() instanceof ThirdRailBlock)) {
            return Attempt.failed(Component.translatable("create.track.original_missing").withStyle(ChatFormatting.RED), null);
        }

        // Only the stored direction's sign is trusted: the axis itself always comes from the block.
        ListTag axisTag = selection.getList(NBT_AXIS, Tag.TAG_DOUBLE);
        Vec3 blockAxis = state1.getValue(ThirdRailBlock.SHAPE).axis();
        double storedDot = axisTag.size() == 3 ? axisTag.getDouble(0) * blockAxis.x + axisTag.getDouble(2) * blockAxis.z : 1;
        Vector3d axis1 = joml(storedDot < 0 ? blockAxis.scale(-1) : blockAxis);

        Vector3d axis2 = ThirdRailPlacementRules.alongLook(joml(target.shape().axis()), joml(player.getLookAngle()));
        BlockPos pos2 = target.pos();
        ThirdRailPlacementRules.Outcome outcome = ThirdRailPlacementRules.evaluate(
            pos1.getX(), pos1.getY(), pos1.getZ(), axis1,
            pos2.getX(), pos2.getY(), pos2.getZ(), axis2,
            maxLength(), maximise
        );

        ThirdRailConnection connection = null;
        if (outcome.hasCurve()) {
            BlockPos owner = step(pos1, outcome.step1(), outcome.extent1());
            BlockPos other = step(pos2, outcome.step2(), outcome.extent2());
            connection = new ThirdRailConnection(owner, other, vec(outcome.end1()), vec(outcome.axis1()), vec(outcome.end2()), vec(outcome.axis2()), true, true, 0);
            connection = connection.withSupportsOnRight(supportsAwayFrom(connection, player.position()));
            connection = connection.withRailCost(connection.computedRailCost());
        }

        if (!outcome.valid()) {
            ChatFormatting colour = "second_point".equals(outcome.problem()) ? ChatFormatting.WHITE : ChatFormatting.RED;
            return Attempt.failed(Component.translatable("create.track." + outcome.problem()).withStyle(colour), connection);
        }

        List<Extension> extensions = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            BlockPos start = side == 0 ? pos1 : pos2;
            Vector3d direction = side == 0 ? outcome.step1() : outcome.step2();
            int extent = side == 0 ? outcome.extent1() : outcome.extent2();
            EThirdRailShape shape = EThirdRailShape.fromAxis(direction.x, direction.z);
            for (int i = 1; i <= extent; i++) {
                BlockPos at = step(start, direction, i);
                BlockState there = level.getBlockState(at);
                if (there.getBlock() instanceof ThirdRailBlock && there.getValue(ThirdRailBlock.SHAPE) == shape) {
                    extensions.add(new Extension(at, there, true));
                } else if (there.canBeReplaced() && !at.equals(pos1) && !at.equals(pos2)) {
                    extensions.add(new Extension(at, ModBlocks.THIRD_RAIL.get().defaultBlockState().setValue(ThirdRailBlock.SHAPE, shape), false));
                } else {
                    return Attempt.failed(Component.translatable("pantographsandwires.third_rail.obstructed").withStyle(ChatFormatting.RED), connection);
                }
            }
        }

        if (level.getBlockEntity(connection.owner()) instanceof ThirdRailBlockEntity first && first.hasConnectionTo(connection.other())) {
            return Attempt.failed(Component.translatable("pantographsandwires.third_rail.already_connected").withStyle(ChatFormatting.RED), connection);
        }

        int newBlocks = (int) extensions.stream().filter(extension -> !extension.existing()).count();
        int needed = connection.railCost() + newBlocks + (target.existing() ? 0 : 1);
        boolean maximisable = !maximise && outcome.hasStraights();
        if (!player.isCreative() && countItems(player.getInventory(), stack.getItem()) < needed) {
            return new Attempt(false, Component.translatable("pantographsandwires.third_rail.not_enough_rails").withStyle(ChatFormatting.RED),
                connection, needed, extensions, maximisable, null, Set.of(), 0);
        }

        Block pavement = player.getOffhandItem().getItem() instanceof BlockItem blockItem
            && ThirdRailPaver.canPaveWith(level, blockItem.getBlock(), pos1) ? blockItem.getBlock() : null;
        Set<BlockPos> paveAt = Set.of();
        int pavementNeeded = 0;
        if (pavement != null) {
            Set<BlockPos> positions = new LinkedHashSet<>();
            positions.add(pos1.below());
            extensions.forEach(extension -> positions.add(extension.pos().below()));
            positions.addAll(ThirdRailPaver.positions(connection));
            positions.add(pos2.below());
            paveAt = positions;
            pavementNeeded = ThirdRailPaver.pave(level, paveAt, pavement, true);
            if (!player.isCreative() && countItems(player.getInventory(), pavement.asItem()) < pavementNeeded) {
                return new Attempt(false, Component.translatable("create.track.not_enough_pavement").withStyle(ChatFormatting.RED),
                    connection, needed, extensions, maximisable, pavement, paveAt, pavementNeeded);
            }
        }
        return new Attempt(true, Component.translatable("create.track.valid_connection").withStyle(ChatFormatting.GREEN),
            connection, needed, extensions, maximisable, pavement, paveAt, pavementNeeded);
    }

    /**
     * Places the end block and any straight rail leading into the curve, lays the rail at both ends, paves
     * under it and uses up the rails and pavement. Server only. Takes nothing and returns false if the rail
     * couldn't be laid.
     */
    public static boolean commit(Level level, Player player, InteractionHand hand, Target target, Attempt attempt) {
        ThirdRailConnection connection = attempt.connection();
        if (connection == null) {
            return false;
        }
        List<BlockPos> placed = new ArrayList<>();
        if (!target.existing()) {
            if (!level.setBlock(target.pos(), target.placementState(), 3)) {
                return false;
            }
            placed.add(target.pos());
        }
        for (Extension extension : attempt.extensions()) {
            if (!extension.existing() && level.setBlock(extension.pos(), extension.state(), 3)) {
                placed.add(extension.pos());
            }
        }
        if (!(level.getBlockEntity(connection.owner()) instanceof ThirdRailBlockEntity first)
            || !(level.getBlockEntity(connection.other()) instanceof ThirdRailBlockEntity second)) {
            placed.forEach(pos -> level.removeBlock(pos, false));
            return false;
        }
        first.addConnection(connection);
        second.addConnection(connection.secondary());
        matchSupports(level, connection, attempt, target);

        int paved = attempt.pavement() == null ? 0 : ThirdRailPaver.pave(level, attempt.paveAt(), attempt.pavement(), false);

        ItemStack held = player.getItemInHand(hand);
        Item rail = held.getItem();
        clearSelection(held);
        if (!player.isCreative()) {
            removeItems(player.getInventory(), rail, attempt.railsNeeded());
            if (paved > 0) {
                removeItems(player.getInventory(), attempt.pavement().asItem(), paved);
            }
        }

        BlockState placedState = level.getBlockState(target.pos());
        SoundType sound = placedState.getSoundType();
        level.playSound(null, target.pos(), sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        return true;
    }

    /**
     * Straight rail leading into a curve has no rail of its own to take its supports' side from, so each
     * block is told the side the curve's supports are on, as seen travelling towards the curve.
     */
    private static void matchSupports(Level level, ThirdRailConnection connection, Attempt attempt, Target target) {
        List<BlockPos> straights = new ArrayList<>();
        attempt.extensions().forEach(extension -> straights.add(extension.pos()));
        straights.add(target.pos());
        for (BlockPos pos : straights) {
            if (pos.equals(connection.owner()) || pos.equals(connection.other())
                || !(level.getBlockEntity(pos) instanceof ThirdRailBlockEntity rail) || !rail.getConnections().isEmpty()) {
                continue;
            }
            boolean nearFirstEnd = pos.distSqr(connection.owner()) <= pos.distSqr(connection.other());
            Vec3 towardsCurve = nearFirstEnd ? connection.axis1() : connection.secondary().axis1();
            boolean onRight = nearFirstEnd ? connection.supportsOnRight() : !connection.supportsOnRight();
            boolean forward = towardsCurve.dot(rail.shape().axis()) > 0;
            rail.setPieceSupportsOnRight(forward == onRight);
        }
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

    /** The block a number of whole steps along an (integer) axis. */
    private static BlockPos step(BlockPos from, Vector3d axis, int steps) {
        return from.offset((int) Math.round(axis.x * steps), 0, (int) Math.round(axis.z * steps));
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
