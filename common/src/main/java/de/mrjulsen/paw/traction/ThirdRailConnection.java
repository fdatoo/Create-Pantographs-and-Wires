package de.mrjulsen.paw.traction;

import javax.annotation.Nullable;

import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One laid third rail between two rail blocks. Like Create's track connections, each end keeps its
 * own copy: the owner's copy starts at the owner, and exactly one of the two copies is primary, so
 * the rail is rendered and checked for shocks once.
 */
public final class ThirdRailConnection {
    /** Longest straight piece of the conductor used for contact and shock checks. */
    public static final double CONDUCTOR_PIECE_LENGTH = 0.5;
    /** The furthest apart two connected rail blocks can be, matching the largest allowed max_length. */
    public static final int MAX_SPAN = 128;

    /** A curve end must sit within this distance of its block's base centre (a diagonal corner is 0.71 away). */
    private static final double END_TOLERANCE = 1.0;

    private static final String NBT_OTHER = "Other";
    private static final String NBT_START1 = "Start1";
    private static final String NBT_AXIS1 = "Axis1";
    private static final String NBT_START2 = "Start2";
    private static final String NBT_AXIS2 = "Axis2";
    private static final String NBT_PRIMARY = "Primary";
    private static final String NBT_SUPPORTS_ON_RIGHT = "SupportsOnRight";
    private static final String NBT_RAIL_COST = "RailCost";
    private static final String NBT_RELATIVE = "Relative";

    private final BlockPos owner;
    private final BlockPos other;
    private final Vec3 start1;
    private final Vec3 axis1;
    private final Vec3 start2;
    private final Vec3 axis2;
    private final boolean primary;
    private final boolean supportsOnRight;
    /** Rails paid when this rail was laid, refunded on removal; 0 when unknown. */
    private final int railCost;

    private ThirdRailCurve curve;
    private double[] conductor;
    private AABB conductorBounds;

    /**
     * @param supportsOnRight whether the cover's supports stand on the right of the rail, travelling
     *                        from owner to other (right is the tangent crossed with up)
     */
    public ThirdRailConnection(BlockPos owner, BlockPos other, Vec3 start1, Vec3 axis1, Vec3 start2, Vec3 axis2, boolean primary, boolean supportsOnRight, int railCost) {
        this.owner = owner;
        this.other = other;
        this.start1 = start1;
        this.axis1 = axis1;
        this.start2 = start2;
        this.axis2 = axis2;
        this.primary = primary;
        this.supportsOnRight = supportsOnRight;
        this.railCost = Math.max(0, railCost);
    }

    public BlockPos owner() {
        return owner;
    }

    public BlockPos other() {
        return other;
    }

    public Vec3 start1() {
        return start1;
    }

    public Vec3 axis1() {
        return axis1;
    }

    public boolean primary() {
        return primary;
    }

    public boolean supportsOnRight() {
        return supportsOnRight;
    }

    /** The same rail seen from the other end. */
    public ThirdRailConnection secondary() {
        return new ThirdRailConnection(other, owner, start2, axis2, start1, axis1, !primary, !supportsOnRight, railCost);
    }

    public ThirdRailConnection withSupportsOnRight(boolean onRight) {
        return new ThirdRailConnection(owner, other, start1, axis1, start2, axis2, primary, onRight, railCost);
    }

    public ThirdRailConnection withRailCost(int cost) {
        return new ThirdRailConnection(owner, other, start1, axis1, start2, axis2, primary, supportsOnRight, cost);
    }

    public ThirdRailCurve curve() {
        if (curve == null) {
            curve = new ThirdRailCurve(joml(start1), joml(axis1), joml(start2), joml(axis2));
        }
        return curve;
    }

    public double[] conductor() {
        if (conductor == null) {
            conductor = curve().conductor(CONDUCTOR_PIECE_LENGTH);
        }
        return conductor;
    }

    public AABB conductorBounds() {
        if (conductorBounds == null) {
            conductorBounds = bounds(conductor());
        }
        return conductorBounds;
    }

    public Vec3 midpoint() {
        Vector3d p = curve().position(curve().parameterAtDistance(curve().length() / 2), new Vector3d());
        return new Vec3(p.x, p.y, p.z);
    }

    /** Rails refunded when this rail is removed: what was paid, or what it would cost for older data. */
    public int railCost() {
        return railCost > 0 ? railCost : computedRailCost();
    }

    /** Rails needed to lay this curve: one per two blocks of length. */
    public int computedRailCost() {
        return Math.max(1, (int) Math.min(MAX_SPAN, Math.ceil(curve().length() / 2)));
    }

    /**
     * Positions are stored relative to the owner block, so a copy of the block carries its rail along:
     * WorldEdit's copy, paste and stack, and schematics, move block entity data without rewriting it.
     */
    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        Vec3 origin = Vec3.atLowerCornerOf(owner);
        tag.putBoolean(NBT_RELATIVE, true);
        tag.put(NBT_OTHER, NbtUtils.writeBlockPos(other.subtract(owner)));
        tag.put(NBT_START1, writeVec(start1.subtract(origin)));
        tag.put(NBT_AXIS1, writeVec(axis1));
        tag.put(NBT_START2, writeVec(start2.subtract(origin)));
        tag.put(NBT_AXIS2, writeVec(axis2));
        tag.putBoolean(NBT_PRIMARY, primary);
        tag.putBoolean(NBT_SUPPORTS_ON_RIGHT, supportsOnRight);
        tag.putInt(NBT_RAIL_COST, railCost());
        return tag;
    }

    /**
     * Reads a stored rail, or null when the data doesn't describe a rail between its two blocks. That
     * rejects damaged or crafted data. Rails saved before positions became relative are in world
     * coordinates, so those are still rejected when their block has been carried somewhere else.
     */
    @Nullable
    public static ThirdRailConnection read(BlockPos owner, CompoundTag tag) {
        if (!tag.contains(NBT_OTHER) || !tag.contains(NBT_START1) || !tag.contains(NBT_START2)
            || !tag.contains(NBT_AXIS1) || !tag.contains(NBT_AXIS2)) {
            return null;
        }
        boolean relative = tag.getBoolean(NBT_RELATIVE);
        Vec3 origin = relative ? Vec3.atLowerCornerOf(owner) : Vec3.ZERO;
        BlockPos other = NbtUtils.readBlockPos(tag.getCompound(NBT_OTHER));
        if (relative) {
            other = owner.offset(other);
        }
        Vec3 start1 = readVec(tag.getList(NBT_START1, Tag.TAG_DOUBLE)).add(origin);
        Vec3 axis1 = readVec(tag.getList(NBT_AXIS1, Tag.TAG_DOUBLE));
        Vec3 start2 = readVec(tag.getList(NBT_START2, Tag.TAG_DOUBLE)).add(origin);
        Vec3 axis2 = readVec(tag.getList(NBT_AXIS2, Tag.TAG_DOUBLE));
        if (!isPlausible(owner, other, start1, axis1, start2, axis2)) {
            return null;
        }
        return new ThirdRailConnection(
            owner, other, start1, axis1, start2, axis2,
            tag.getBoolean(NBT_PRIMARY),
            tag.getBoolean(NBT_SUPPORTS_ON_RIGHT),
            Math.min(MAX_SPAN, tag.getInt(NBT_RAIL_COST))
        );
    }

    static boolean isPlausible(BlockPos owner, BlockPos other, Vec3 start1, Vec3 axis1, Vec3 start2, Vec3 axis2) {
        if (owner.equals(other) || owner.distSqr(other) > (double) MAX_SPAN * MAX_SPAN) {
            return false;
        }
        if (!finite(start1) || !finite(axis1) || !finite(start2) || !finite(axis2)) {
            return false;
        }
        if (start1.distanceTo(Vec3.atBottomCenterOf(owner)) > END_TOLERANCE || start2.distanceTo(Vec3.atBottomCenterOf(other)) > END_TOLERANCE) {
            return false;
        }
        return horizontalLength(axis1) > 0.5 && horizontalLength(axis2) > 0.5;
    }

    /** The smallest box around a flattened polyline. */
    public static AABB bounds(double[] xyz) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i + 2 < xyz.length; i += 3) {
            minX = Math.min(minX, xyz[i]);
            minY = Math.min(minY, xyz[i + 1]);
            minZ = Math.min(minZ, xyz[i + 2]);
            maxX = Math.max(maxX, xyz[i]);
            maxY = Math.max(maxY, xyz[i + 1]);
            maxZ = Math.max(maxZ, xyz[i + 2]);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static double horizontalLength(Vec3 v) {
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    private static Vector3d joml(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }

    private static ListTag writeVec(Vec3 v) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(v.x));
        list.add(DoubleTag.valueOf(v.y));
        list.add(DoubleTag.valueOf(v.z));
        return list;
    }

    private static Vec3 readVec(ListTag list) {
        if (list.size() != 3) {
            return new Vec3(Double.NaN, Double.NaN, Double.NaN);
        }
        return new Vec3(list.getDouble(0), list.getDouble(1), list.getDouble(2));
    }
}
