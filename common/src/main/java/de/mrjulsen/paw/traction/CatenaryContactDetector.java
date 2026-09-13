package de.mrjulsen.paw.traction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.joml.Vector3d;

import de.mrjulsen.paw.traction.PantographContactGeometry.ContactResult;
import de.mrjulsen.paw.traction.PantographContactGeometry.WireSegment;
import de.mrjulsen.wires.WireClientNetwork;
import de.mrjulsen.wires.WireCollision.WireBlockCollision;
import de.mrjulsen.wires.WireNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Adapts catenary network collisions to collector-local contact geometry. */
public final class CatenaryContactDetector {
    // A valid 2.5 x 3.6 collector occupies only a few dozen voxel candidates.
    // Keep generous headroom for skewed frames while bounding malformed input work.
    static final long MAX_SWEEP_BLOCKS = 512;

    /**
     * Central collision-work budget for one 512-block sweep. Real network queries
     * normally return only a handful of collisions; this limit leaves ample
     * headroom while bounding a hostile or malformed per-block collection.
     */
    static final long MAX_COLLISIONS_PER_BLOCK = 1_024;
    static final long MAX_TOTAL_COLLISION_DATA = 16_384;

    // Diagnostics for the F3 overlay: how much the last sweep actually looked at.
    // Written from the client tick only, read for display.
    public static volatile int lastSweepBlocks;
    public static volatile int lastCollisionCount;

    private static final double UNIT_ROUNDOFF = 0x1.0p-53;
    // Covers the FMA expression, absolute-magnitude accumulation, and bound rounding.
    private static final double SAT_FILTER_GAMMA =
        32 * UNIT_ROUNDOFF / (1 - 32 * UNIT_ROUNDOFF);
    private static final double SAT_FILTER_UNDERFLOW = 32 * Double.MIN_VALUE;
    private static final BigDecimal EXACT_HALF = new BigDecimal(0.5);

    private CatenaryContactDetector() {}

    public static ContactResult findContact(
        Level level,
        Vector3d worldPosition,
        Vector3d upVector,
        Vector3d rightVector
    ) {
        if (level == null) {
            return ContactResult.none();
        }
        return findContact(
            worldPosition,
            upVector,
            rightVector,
            level.isClientSide(),
            clientSide -> productionCollisionSource(level, clientSide)
        );
    }

    private static CollisionSource productionCollisionSource(Level level, boolean clientSide) {
        if (clientSide) {
            return ClientCollisionSource.create(level);
        }
        WireNetwork net = WireNetwork.get(level);
        return net == null ? null : adaptCollisionLookup(net::getCollisionsInBlock);
    }

    @Environment(EnvType.CLIENT)
    private static final class ClientCollisionSource {
        private ClientCollisionSource() {}

        private static CollisionSource create(Level level) {
            WireClientNetwork net = WireClientNetwork.get(level);
            return net == null ? null : adaptCollisionLookup(net::getCollisionsInBlock);
        }
    }

    static CollisionSource adaptCollisionLookup(BlockCollisionLookup lookup) {
        return queryPos -> {
            Iterable<WireBlockCollision> collisions = lookup.collisionsInBlock(queryPos);
            if (collisions == null) {
                return null;
            }
            List<CollisionData> flattened = new ArrayList<>();
            long entries = 0;
            Iterator<WireBlockCollision> iterator = collisions.iterator();
            while (iterator.hasNext()) {
                if (entries >= MAX_COLLISIONS_PER_BLOCK) {
                    return null;
                }
                WireBlockCollision collision = iterator.next();
                entries = checkedIncrement(entries, MAX_COLLISIONS_PER_BLOCK);
                if (entries < 0 || collision == null
                    || collision.pos() == null || collision.entryPointA() == null
                    || collision.entryPointB() == null) {
                    return null;
                }
                flattened.add(new CollisionData(
                    collision,
                    collision.pos(),
                    new Vector3d(collision.entryPointA()),
                    new Vector3d(collision.entryPointB()),
                    collision
                ));
            }
            return flattened;
        };
    }

    static ContactResult findContact(
        Vector3d worldPosition,
        Vector3d upVector,
        Vector3d rightVector,
        boolean clientSide,
        CollisionSourceSelector selector
    ) {
        PreparedSweep sweep = PreparedSweep.prepare(worldPosition, upVector, rightVector);
        if (sweep == null) {
            return ContactResult.none();
        }

        CollisionSource source;
        try {
            source = selector == null ? null : selector.select(clientSide);
        } catch (RuntimeException exception) {
            return ContactResult.none();
        }
        return findContact(sweep, source);
    }

    static ContactResult findContact(
        Vector3d worldPosition,
        Vector3d upVector,
        Vector3d rightVector,
        CollisionSource source
    ) {
        PreparedSweep sweep = PreparedSweep.prepare(worldPosition, upVector, rightVector);
        return sweep == null ? ContactResult.none() : findContact(sweep, source);
    }

    private static ContactResult findContact(PreparedSweep sweep, CollisionSource source) {
        if (source == null) {
            return ContactResult.none();
        }
        List<CollisionData> validatedCollisions = new ArrayList<>();
        long totalCollisions = 0;
        lastSweepBlocks = sweep.intersectingBlocks().size();
        lastCollisionCount = 0;
        try {
            for (BlockPos queryPos : sweep.intersectingBlocks()) {
                Iterable<CollisionData> collisionData = source.collisionsInBlock(queryPos);
                if (collisionData == null) {
                    return ContactResult.none();
                }
                Iterator<CollisionData> collisions = collisionData.iterator();
                while (collisions.hasNext()) {
                    if (totalCollisions >= MAX_TOTAL_COLLISION_DATA) {
                        return ContactResult.none();
                    }
                    CollisionData collision = collisions.next();
                    totalCollisions = checkedIncrement(totalCollisions, MAX_TOTAL_COLLISION_DATA);
                    if (totalCollisions < 0 || !isValid(collision)) {
                        return ContactResult.none();
                    }
                    validatedCollisions.add(collision);
                }
            }

            lastCollisionCount = validatedCollisions.size();
            List<WireSegment> segments = new ArrayList<>();
            Set<Object> encounteredIdentities = new HashSet<>();
            for (CollisionData collision : validatedCollisions) {
                if (encounteredIdentities.add(collision.identity())) {
                    segments.add(toLocalSegment(sweep.anchor(), collision));
                }
            }
            return PantographContactGeometry.findContact(
                sweep.collectorBase(),
                sweep.upVector(),
                sweep.rightVector(),
                segments
            );
        } catch (RuntimeException exception) {
            return ContactResult.none();
        }
    }

    private static long checkedIncrement(long count, long limit) {
        try {
            long incremented = Math.addExact(count, 1);
            return incremented <= limit ? incremented : -1;
        } catch (ArithmeticException exception) {
            return -1;
        }
    }

    private static boolean isValid(CollisionData collision) {
        return collision != null
            && collision.identity() != null
            && collision.blockPos() != null
            && isFinite(collision.in())
            && isFinite(collision.out());
    }

    static WireSegment toLocalSegment(BlockPos anchor, CollisionData collision) {
        BlockPos collisionPos = collision.blockPos();
        Vector3d in = collision.in();
        Vector3d out = collision.out();
        double blockX = (double) collisionPos.getX() - anchor.getX();
        double blockY = (double) collisionPos.getY() - anchor.getY();
        double blockZ = (double) collisionPos.getZ() - anchor.getZ();
        return new WireSegment(
            blockX + in.x,
            blockY + in.y,
            blockZ + in.z,
            blockX + out.x,
            blockY + out.y,
            blockZ + out.z,
            collision.source()
        );
    }

    private static boolean isFinite(Vector3d vector) {
        return vector != null
            && Double.isFinite(vector.x)
            && Double.isFinite(vector.y)
            && Double.isFinite(vector.z);
    }

    private record PreparedSweep(
        Vector3d upVector,
        Vector3d rightVector,
        List<BlockPos> intersectingBlocks,
        BlockPos anchor,
        Vector3d collectorBase
    ) {
        private static PreparedSweep prepare(
            Vector3d worldPosition,
            Vector3d upVector,
            Vector3d rightVector
        ) {
            Vector3d position = worldPosition == null ? null : new Vector3d(worldPosition);
            Vector3d up = upVector == null ? null : new Vector3d(upVector);
            Vector3d right = rightVector == null ? null : new Vector3d(rightVector);
            if (!isFinite(position)
                || !PantographContactGeometry.isValidCollectorFrame(up, right)
                || !SweepRange.supportedHorizontal(position.x)
                || !SweepRange.supportedHorizontal(position.z)
                || !SweepRange.supportedInteger(Math.floor(position.y))) {
                return null;
            }

            Vector3d collectorLeft = new Vector3d(position).sub(right);
            Vector3d collectorRight = new Vector3d(position).add(right);
            List<BlockPos> intersections = findIntersectingBlocks(
                collectorLeft,
                collectorRight,
                up
            );
            if (intersections == null) {
                return null;
            }

            BlockPos anchor = BlockPos.containing(position.x, position.y, position.z);
            Vector3d collectorBase = new Vector3d(
                position.x - anchor.getX(),
                position.y - anchor.getY(),
                position.z - anchor.getZ()
            );
            return isFinite(collectorBase)
                ? new PreparedSweep(up, right, intersections, anchor, collectorBase)
                : null;
        }
    }

    private static List<BlockPos> findIntersectingBlocks(Vector3d left, Vector3d right, Vector3d upVector) {
        Vector3d upperLeft = new Vector3d(left).add(upVector);
        Vector3d upperRight = new Vector3d(right).add(upVector);
        if (!isFinite(left) || !isFinite(right) || !isFinite(upVector)
            || !isFinite(upperLeft) || !isFinite(upperRight)) {
            return null;
        }
        Vector3d min = new Vector3d(left).min(right).min(upperLeft).min(upperRight);
        Vector3d max = new Vector3d(left).max(right).max(upperLeft).max(upperRight);
        SweepRange range = SweepRange.closedFloors(min, max);
        if (range == null) {
            return null;
        }
        List<BlockPos> intersections = new ArrayList<>();

        for (long x = range.minX(); x <= range.maxX(); x++) {
            for (long y = range.minY(); y <= range.maxY(); y++) {
                for (long z = range.minZ(); z <= range.maxZ(); z++) {
                    BlockPos block = new BlockPos((int) x, (int) y, (int) z);
                    if (triangleIntersectsBlock(left, right, upperLeft, block)
                        || triangleIntersectsBlock(upperLeft, right, upperRight, block)) {
                        intersections.add(block);
                    }
                }
            }
        }
        return intersections;
    }

    private record SweepRange(long minX, long maxX, long minY, long maxY, long minZ, long maxZ) {
        private static SweepRange closedFloors(Vector3d min, Vector3d max) {
            double minXFloor = closedMinimum(min.x);
            double maxXFloor = Math.floor(max.x);
            double minYFloor = closedMinimum(min.y);
            double maxYFloor = Math.floor(max.y);
            double minZFloor = closedMinimum(min.z);
            double maxZFloor = Math.floor(max.z);
            if (!supportedHorizontal(minXFloor) || !supportedHorizontal(maxXFloor)
                || !supportedInteger(minYFloor) || !supportedInteger(maxYFloor)
                || !supportedHorizontal(minZFloor) || !supportedHorizontal(maxZFloor)) {
                return null;
            }

            long minX = (long) minXFloor;
            long maxX = (long) maxXFloor;
            long minY = (long) minYFloor;
            long maxY = (long) maxYFloor;
            long minZ = (long) minZFloor;
            long maxZ = (long) maxZFloor;
            try {
                long xSpan = Math.addExact(Math.subtractExact(maxX, minX), 1);
                long ySpan = Math.addExact(Math.subtractExact(maxY, minY), 1);
                long zSpan = Math.addExact(Math.subtractExact(maxZ, minZ), 1);
                long candidates = Math.multiplyExact(Math.multiplyExact(xSpan, ySpan), zSpan);
                if (xSpan <= 0 || ySpan <= 0 || zSpan <= 0 || candidates > MAX_SWEEP_BLOCKS) {
                    return null;
                }
            } catch (ArithmeticException exception) {
                return null;
            }
            return new SweepRange(minX, maxX, minY, maxY, minZ, maxZ);
        }

        private static double closedMinimum(double coordinate) {
            double floor = Math.floor(coordinate);
            return coordinate == floor ? floor - 1 : floor;
        }

        private static boolean supportedHorizontal(double coordinate) {
            return coordinate >= -Level.MAX_LEVEL_SIZE && coordinate < Level.MAX_LEVEL_SIZE;
        }

        private static boolean supportedInteger(double coordinate) {
            return coordinate >= Integer.MIN_VALUE && coordinate <= Integer.MAX_VALUE;
        }
    }

    private static boolean triangleIntersectsBlock(
        Vector3d first,
        Vector3d second,
        Vector3d third,
        BlockPos block
    ) {
        return triangleIntersectsBlock(first, second, third, block, null);
    }

    static SatDiagnostics triangleIntersectsBlockWithDiagnostics(
        Vector3d first,
        Vector3d second,
        Vector3d third,
        BlockPos block
    ) {
        SatUsage usage = new SatUsage();
        boolean intersects = triangleIntersectsBlock(first, second, third, block, usage);
        return new SatDiagnostics(intersects, usage.fastSignCount, usage.exactSignCount);
    }

    static boolean axisCanBeNormalizedExactly(Vector3d axis) {
        return NormalizedAxis.of(axis) != null;
    }

    private static boolean triangleIntersectsBlock(
        Vector3d first,
        Vector3d second,
        Vector3d third,
        BlockPos block,
        SatUsage usage
    ) {
        if (!isFinite(first) || !isFinite(second) || !isFinite(third) || block == null) {
            return true;
        }
        Vector3d blockCenter = new Vector3d(
            block.getX() + 0.5,
            block.getY() + 0.5,
            block.getZ() + 0.5
        );
        // Project only block-center-local vertices. At world-scale coordinates,
        // separate world projections lose the ulps that distinguish a closed
        // face from Math.nextUp/nextDown on the adjacent side.
        Vector3d localFirst = new Vector3d(first).sub(blockCenter);
        Vector3d localSecond = new Vector3d(second).sub(blockCenter);
        Vector3d localThird = new Vector3d(third).sub(blockCenter);
        Vector3d edgeA = new Vector3d(localSecond).sub(localFirst);
        Vector3d edgeB = new Vector3d(localThird).sub(localSecond);
        Vector3d edgeC = new Vector3d(localFirst).sub(localThird);
        Vector3d normal = new Vector3d(edgeA).cross(new Vector3d(localThird).sub(localFirst));
        Vector3d[] axes = {
            new Vector3d(1, 0, 0),
            new Vector3d(0, 1, 0),
            new Vector3d(0, 0, 1),
            normal,
            crossWithX(edgeA), crossWithY(edgeA), crossWithZ(edgeA),
            crossWithX(edgeB), crossWithY(edgeB), crossWithZ(edgeB),
            crossWithX(edgeC), crossWithY(edgeC), crossWithZ(edgeC)
        };
        for (Vector3d axis : axes) {
            if (separates(axis, localFirst, localSecond, localThird, usage)) {
                return false;
            }
        }
        return true;
    }

    private static Vector3d crossWithX(Vector3d vector) {
        return new Vector3d(0, vector.z, -vector.y);
    }

    private static Vector3d crossWithY(Vector3d vector) {
        return new Vector3d(-vector.z, 0, vector.x);
    }

    private static Vector3d crossWithZ(Vector3d vector) {
        return new Vector3d(vector.y, -vector.x, 0);
    }

    private static boolean separates(
        Vector3d axis,
        Vector3d first,
        Vector3d second,
        Vector3d third,
        SatUsage usage
    ) {
        NormalizedAxis normalized = NormalizedAxis.of(axis);
        if (normalized == null) {
            return false;
        }
        return allStrictlyAbove(normalized, first, second, third, usage)
            || allStrictlyBelow(normalized, first, second, third, usage);
    }

    private static boolean allStrictlyAbove(
        NormalizedAxis axis,
        Vector3d first,
        Vector3d second,
        Vector3d third,
        SatUsage usage
    ) {
        return filteredProjectionSign(axis, first, -1, usage) > 0
            && filteredProjectionSign(axis, second, -1, usage) > 0
            && filteredProjectionSign(axis, third, -1, usage) > 0;
    }

    private static boolean allStrictlyBelow(
        NormalizedAxis axis,
        Vector3d first,
        Vector3d second,
        Vector3d third,
        SatUsage usage
    ) {
        return filteredProjectionSign(axis, first, 1, usage) < 0
            && filteredProjectionSign(axis, second, 1, usage) < 0
            && filteredProjectionSign(axis, third, 1, usage) < 0;
    }

    /** Classifies dot(axis, vertex) + radiusSign * AABB-radius. */
    private static int filteredProjectionSign(
        NormalizedAxis axis,
        Vector3d vertex,
        int radiusSign,
        SatUsage usage
    ) {
        double zProduct = axis.z * vertex.z;
        double dot = Math.fma(axis.y, vertex.y, zProduct);
        dot = Math.fma(axis.x, vertex.x, dot);
        double radius = 0.5 * (Math.abs(axis.x) + Math.abs(axis.y) + Math.abs(axis.z));
        double estimate = radiusSign < 0 ? dot - radius : dot + radius;
        double magnitude = Math.abs(axis.x * vertex.x)
            + Math.abs(axis.y * vertex.y)
            + Math.abs(axis.z * vertex.z)
            + radius;
        if (!Double.isFinite(estimate) || !Double.isFinite(magnitude)) {
            // Exact arithmetic is not an escape hatch for a missing finite scaling path.
            return 0;
        }
        double errorBound = Math.nextUp(
            SAT_FILTER_GAMMA * magnitude + SAT_FILTER_UNDERFLOW
        );
        if (!Double.isFinite(errorBound)) {
            return 0;
        }
        if (estimate > errorBound) {
            if (usage != null) {
                usage.fastSignCount++;
            }
            return 1;
        }
        if (estimate < -errorBound) {
            if (usage != null) {
                usage.fastSignCount++;
            }
            return -1;
        }

        if (usage != null) {
            usage.exactSignCount++;
        }
        try {
            BigDecimal exactDot = new BigDecimal(axis.x).multiply(new BigDecimal(vertex.x))
                .add(new BigDecimal(axis.y).multiply(new BigDecimal(vertex.y)))
                .add(new BigDecimal(axis.z).multiply(new BigDecimal(vertex.z)));
            BigDecimal exactRadius = new BigDecimal(Math.abs(axis.x))
                .add(new BigDecimal(Math.abs(axis.y)))
                .add(new BigDecimal(Math.abs(axis.z)))
                .multiply(EXACT_HALF);
            return (radiusSign < 0
                ? exactDot.subtract(exactRadius)
                : exactDot.add(exactRadius)).signum();
        } catch (NumberFormatException | ArithmeticException exception) {
            // Broad-phase uncertainty must not omit a possible closed-set contact.
            return 0;
        }
    }

    private record NormalizedAxis(double x, double y, double z) {
        private static NormalizedAxis of(Vector3d axis) {
            if (!isFinite(axis)) {
                return null;
            }
            double maxAbs = Math.max(Math.abs(axis.x), Math.max(Math.abs(axis.y), Math.abs(axis.z)));
            if (maxAbs == 0) {
                return null;
            }

            int shift;
            if (maxAbs >= Double.MIN_NORMAL) {
                shift = -Math.getExponent(maxAbs);
            } else {
                double lifted = Math.scalb(maxAbs, -Double.MIN_EXPONENT);
                if (!Double.isFinite(lifted) || lifted == 0) {
                    return null;
                }
                shift = -Double.MIN_EXPONENT - Math.getExponent(lifted);
            }
            double x = Math.scalb(axis.x, shift);
            double y = Math.scalb(axis.y, shift);
            double z = Math.scalb(axis.z, shift);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || axis.x != 0 && x == 0
                || axis.y != 0 && y == 0
                || axis.z != 0 && z == 0
                || !sameBits(Math.scalb(x, -shift), axis.x)
                || !sameBits(Math.scalb(y, -shift), axis.y)
                || !sameBits(Math.scalb(z, -shift), axis.z)) {
                return null;
            }
            return new NormalizedAxis(x, y, z);
        }

        private static boolean sameBits(double first, double second) {
            return Double.doubleToRawLongBits(first) == Double.doubleToRawLongBits(second);
        }
    }

    static record SatDiagnostics(boolean intersects, int fastSignCount, int exactSignCount) {}

    private static final class SatUsage {
        private int fastSignCount;
        private int exactSignCount;
    }
}

@FunctionalInterface
interface BlockCollisionLookup {
    Iterable<WireBlockCollision> collisionsInBlock(BlockPos blockPos);
}

@FunctionalInterface
interface CollisionSourceSelector {
    CollisionSource select(boolean clientSide);
}

@FunctionalInterface
interface CollisionSource {
    Iterable<CollisionData> collisionsInBlock(BlockPos blockPos);
}

record CollisionData(
    Object identity,
    BlockPos blockPos,
    Vector3d in,
    Vector3d out,
    Object source
) {}
