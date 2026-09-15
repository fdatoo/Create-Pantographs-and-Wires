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
 *
 * The rail is a Bezier between its ends, or, when it was laid along a real track, a path of points
 * following that track (CreateTrackRoute).
 */
public final class ThirdRailConnection {
    /** Longest straight piece of the conductor used for contact and shock checks. */
    public static final double CONDUCTOR_PIECE_LENGTH = 0.5;
    /** The furthest apart two connected rail blocks can be, matching the largest allowed max_length. */
    public static final int MAX_SPAN = 128;
    /** Most points a stored path may have: well over MAX_SPAN of winding track at TrackFollowPath.SPACING. */
    public static final int MAX_PATH_POINTS = 2048;

    /** A curve end must sit within this distance of its block's base centre (a diagonal corner is 0.71 away). */
    private static final double END_TOLERANCE = 1.0;
    /** Longest gap allowed between neighbouring points of a stored path. */
    private static final double MAX_PATH_GAP = 2.0;

    private static final String NBT_OTHER = "Other";
    private static final String NBT_START1 = "Start1";
    private static final String NBT_AXIS1 = "Axis1";
    private static final String NBT_START2 = "Start2";
    private static final String NBT_AXIS2 = "Axis2";
    private static final String NBT_PRIMARY = "Primary";
    private static final String NBT_SUPPORTS_ON_RIGHT = "SupportsOnRight";
    private static final String NBT_RAIL_COST = "RailCost";
    private static final String NBT_RELATIVE = "Relative";
    private static final String NBT_PATH = "Path";

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
    /** The followed path as flattened world x, y, z from this end to the other, or null for a Bezier. */
    @Nullable
    private final double[] path;

    private ThirdRailCurve curve;
    private double[] conductor;
    private AABB conductorBounds;

    /**
     * @param supportsOnRight whether the cover's supports stand on the right of the rail, travelling
     *                        from owner to other (right is the tangent crossed with up)
     */
    public ThirdRailConnection(BlockPos owner, BlockPos other, Vec3 start1, Vec3 axis1, Vec3 start2, Vec3 axis2, boolean primary, boolean supportsOnRight, int railCost) {
        this(owner, other, start1, axis1, start2, axis2, primary, supportsOnRight, railCost, null);
    }

    private ThirdRailConnection(BlockPos owner, BlockPos other, Vec3 start1, Vec3 axis1, Vec3 start2, Vec3 axis2, boolean primary, boolean supportsOnRight, int railCost,
        @Nullable double[] path) {
        this.owner = owner;
        this.other = other;
        this.start1 = start1;
        this.axis1 = axis1;
        this.start2 = start2;
        this.axis2 = axis2;
        this.primary = primary;
        this.supportsOnRight = supportsOnRight;
        this.railCost = Math.max(0, railCost);
        this.path = path;
    }

    /**
     * A rail following a path from owner to other. Its ends and their directions come from the path.
     *
     * @param path flattened world x, y, z points, at least two
     */
    public static ThirdRailConnection alongPath(BlockPos owner, BlockPos other, double[] path, boolean primary, boolean supportsOnRight, int railCost) {
        int last = path.length / 3 - 1;
        Vec3 start1 = new Vec3(path[0], path[1], path[2]);
        Vec3 start2 = new Vec3(path[last * 3], path[last * 3 + 1], path[last * 3 + 2]);
        Vec3 axis1 = horizontalDirection(path, 0, 1);
        Vec3 axis2 = horizontalDirection(path, last, last - 1);
        return new ThirdRailConnection(owner, other, start1, axis1, start2, axis2, primary, supportsOnRight, railCost, path.clone());
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

    /** Whether the rail follows a track's path rather than being a Bezier. */
    public boolean followsPath() {
        return path != null;
    }

    /** The same rail seen from the other end. */
    public ThirdRailConnection secondary() {
        return new ThirdRailConnection(other, owner, start2, axis2, start1, axis1, !primary, !supportsOnRight, railCost, reversed(path));
    }

    public ThirdRailConnection withSupportsOnRight(boolean onRight) {
        return new ThirdRailConnection(owner, other, start1, axis1, start2, axis2, primary, onRight, railCost, path);
    }

    public ThirdRailConnection withRailCost(int cost) {
        return new ThirdRailConnection(owner, other, start1, axis1, start2, axis2, primary, supportsOnRight, cost, path);
    }

    public ThirdRailCurve curve() {
        if (curve == null) {
            curve = path != null ? ThirdRailCurve.along(path) : new ThirdRailCurve(joml(start1), joml(axis1), joml(start2), joml(axis2));
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
        if (path != null) {
            ListTag points = new ListTag();
            for (int i = 0; i < path.length; i++) {
                double originAxis = i % 3 == 0 ? origin.x : i % 3 == 1 ? origin.y : origin.z;
                points.add(DoubleTag.valueOf(path[i] - originAxis));
            }
            tag.put(NBT_PATH, points);
        }
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
        double[] path = null;
        if (tag.contains(NBT_PATH)) {
            path = readPath(tag.getList(NBT_PATH, Tag.TAG_DOUBLE), origin, start1, start2);
            if (path == null) {
                return null;
            }
        }
        return new ThirdRailConnection(
            owner, other, start1, axis1, start2, axis2,
            tag.getBoolean(NBT_PRIMARY),
            tag.getBoolean(NBT_SUPPORTS_ON_RIGHT),
            Math.min(MAX_SPAN, tag.getInt(NBT_RAIL_COST)),
            path
        );
    }

    /** A stored path, or null unless it is finite, bounded, unbroken and runs from start1 to start2. */
    @Nullable
    private static double[] readPath(ListTag list, Vec3 origin, Vec3 start1, Vec3 start2) {
        int count = list.size() / 3;
        if (list.size() % 3 != 0 || count < 2 || count > MAX_PATH_POINTS) {
            return null;
        }
        double[] path = new double[count * 3];
        for (int i = 0; i < path.length; i++) {
            double originAxis = i % 3 == 0 ? origin.x : i % 3 == 1 ? origin.y : origin.z;
            path[i] = list.getDouble(i) + originAxis;
            if (!Double.isFinite(path[i])) {
                return null;
            }
        }
        for (int i = 1; i < count; i++) {
            double dx = path[i * 3] - path[(i - 1) * 3];
            double dy = path[i * 3 + 1] - path[(i - 1) * 3 + 1];
            double dz = path[i * 3 + 2] - path[(i - 1) * 3 + 2];
            if (dx * dx + dy * dy + dz * dz > MAX_PATH_GAP * MAX_PATH_GAP) {
                return null;
            }
        }
        int last = count - 1;
        if (new Vec3(path[0], path[1], path[2]).distanceTo(start1) > 1e-3
            || new Vec3(path[last * 3], path[last * 3 + 1], path[last * 3 + 2]).distanceTo(start2) > 1e-3) {
            return null;
        }
        return path;
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

    /** The unit horizontal direction from one path point towards another, looking a little further along for stability. */
    private static Vec3 horizontalDirection(double[] path, int from, int towards) {
        int count = path.length / 3;
        int step = towards > from ? 1 : -1;
        int to = Math.max(0, Math.min(count - 1, from + step * 2));
        double dx = path[to * 3] - path[from * 3];
        double dz = path[to * 3 + 2] - path[from * 3 + 2];
        double length = Math.sqrt(dx * dx + dz * dz);
        return length > 1e-9 ? new Vec3(dx / length, 0, dz / length) : new Vec3(0, 0, 1);
    }

    @Nullable
    private static double[] reversed(@Nullable double[] path) {
        if (path == null) {
            return null;
        }
        int count = path.length / 3;
        double[] result = new double[path.length];
        for (int i = 0; i < count; i++) {
            System.arraycopy(path, (count - 1 - i) * 3, result, i * 3, 3);
        }
        return result;
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
