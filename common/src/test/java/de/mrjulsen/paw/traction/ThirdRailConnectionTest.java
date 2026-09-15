package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.phys.Vec3;

class ThirdRailConnectionTest {

    private static final BlockPos A = new BlockPos(2, 200, 2);
    private static final BlockPos B = new BlockPos(2, 200, 12);

    private static ThirdRailConnection straight(int cost) {
        return new ThirdRailConnection(A, B, new Vec3(2.5, 200, 3), new Vec3(0, 0, 1), new Vec3(2.5, 200, 12), new Vec3(0, 0, -1), true, true, cost);
    }

    @Test
    void roundTripKeepsTheRailAndWhatWasPaid() {
        ThirdRailConnection read = ThirdRailConnection.read(A, straight(7).write());
        assertNotNull(read);
        assertEquals(B, read.other());
        assertTrue(read.primary());
        assertEquals(7, read.railCost());
        assertEquals(straight(7).curve().length(), read.curve().length(), 1e-9);
    }

    @Test
    void secondaryCopyRefundsTheSameAsThePrimary() {
        ThirdRailConnection primary = straight(7);
        assertEquals(primary.railCost(), primary.secondary().railCost());
        assertFalse(primary.secondary().primary());
    }

    @Test
    void olderDataWithoutACostFallsBackToTheCurve() {
        CompoundTag tag = straight(7).write();
        tag.remove("RailCost");
        ThirdRailConnection read = ThirdRailConnection.read(A, tag);
        assertNotNull(read);
        assertEquals(read.computedRailCost(), read.railCost());
    }

    @Test
    void endsFarFromTheirBlocksAreRejected() {
        CompoundTag tag = straight(7).write();
        tag.put("Start2", vec(1e10, 0, 0));
        assertNull(ThirdRailConnection.read(A, tag));
    }

    @Test
    void aCopiedRailBlockCarriesItsRailAlong() {
        // What WorldEdit's //stack or a schematic does: the same data loaded into a block further along.
        BlockPos moved = A.offset(0, 0, 40);
        ThirdRailConnection read = ThirdRailConnection.read(moved, straight(7).write());
        assertNotNull(read);
        assertEquals(B.offset(0, 0, 40), read.other());
        assertEquals(new Vec3(2.5, 200, 43), read.start1());
        assertEquals(straight(7).curve().length(), read.curve().length(), 1e-9);
    }

    @Test
    void olderWorldCoordinateDataStillLoadsButNotAtAnotherOwner() {
        CompoundTag old = new CompoundTag();
        old.put("Other", NbtUtils.writeBlockPos(B));
        old.put("Start1", vec(2.5, 200, 3));
        old.put("Axis1", vec(0, 0, 1));
        old.put("Start2", vec(2.5, 200, 12));
        old.put("Axis2", vec(0, 0, -1));
        old.putBoolean("Primary", true);
        old.putInt("RailCost", 7);
        ThirdRailConnection read = ThirdRailConnection.read(A, old);
        assertNotNull(read);
        assertEquals(B, read.other());
        assertNull(ThirdRailConnection.read(A.offset(0, 0, -5), old));
    }

    @Test
    void nonFiniteOrMissingVectorsAreRejected() {
        CompoundTag nan = straight(7).write();
        nan.put("Axis1", vec(Double.NaN, 0, 1));
        assertNull(ThirdRailConnection.read(A, nan));

        CompoundTag missing = straight(7).write();
        missing.remove("Axis2");
        assertNull(ThirdRailConnection.read(A, missing));
    }

    @Test
    void blocksBeyondTheLongestSpanAreRejected() {
        BlockPos far = A.offset(0, 0, ThirdRailConnection.MAX_SPAN + 1);
        ThirdRailConnection tooLong = new ThirdRailConnection(A, far, new Vec3(2.5, 200, 3), new Vec3(0, 0, 1),
            Vec3.atBottomCenterOf(far).add(0, 0, -0.5), new Vec3(0, 0, -1), true, true, 1);
        assertNull(ThirdRailConnection.read(A, tooLong.write()));
    }

    @Test
    void storedCostIsCapped() {
        CompoundTag tag = straight(7).write();
        tag.putInt("RailCost", 1_000_000);
        ThirdRailConnection read = ThirdRailConnection.read(A, tag);
        assertNotNull(read);
        assertEquals(ThirdRailConnection.MAX_SPAN, read.railCost());
    }

    private static double[] pathBeside(int length) {
        double[] path = new double[(length * 2 + 1) * 3];
        for (int i = 0; i <= length * 2; i++) {
            path[i * 3] = 2.5 + Math.sin(i / 8.0) * 0.4;
            path[i * 3 + 1] = 200;
            path[i * 3 + 2] = 2.5 + i * 0.5;
        }
        path[path.length - 3] = 2.5;
        return path;
    }

    @Test
    void aFollowedPathSurvivesSavingAndMovesWithItsBlock() {
        BlockPos end = A.offset(0, 0, 10);
        ThirdRailConnection rail = ThirdRailConnection.alongPath(A, end, pathBeside(10), true, true, 5);
        assertTrue(rail.followsPath());

        ThirdRailConnection read = ThirdRailConnection.read(A, rail.write());
        assertNotNull(read);
        assertTrue(read.followsPath());
        assertEquals(rail.curve().length(), read.curve().length(), 1e-9);

        ThirdRailConnection moved = ThirdRailConnection.read(A.offset(7, 3, 0), rail.write());
        assertNotNull(moved);
        assertEquals(rail.curve().length(), moved.curve().length(), 1e-9);
        assertEquals(rail.midpoint().x + 7, moved.midpoint().x, 1e-9);
        assertEquals(rail.midpoint().y + 3, moved.midpoint().y, 1e-9);
    }

    @Test
    void theOtherEndSeesThePathReversed() {
        ThirdRailConnection rail = ThirdRailConnection.alongPath(A, A.offset(0, 0, 10), pathBeside(10), true, true, 5);
        ThirdRailConnection other = rail.secondary();
        assertEquals(rail.curve().length(), other.curve().length(), 1e-9);
        assertEquals(rail.midpoint().z, other.midpoint().z, 1e-9);
        assertEquals(12.5, other.start1().z, 1e-9);
    }

    @Test
    void aBrokenPathIsRejected() {
        ThirdRailConnection rail = ThirdRailConnection.alongPath(A, A.offset(0, 0, 10), pathBeside(10), true, true, 5);
        CompoundTag gap = rail.write();
        ListTag points = gap.getList("Path", 6);
        points.set(20 * 3 + 2, DoubleTag.valueOf(40));
        assertNull(ThirdRailConnection.read(A, gap));
    }

    private static ListTag vec(double x, double y, double z) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(x));
        list.add(DoubleTag.valueOf(y));
        list.add(DoubleTag.valueOf(z));
        return list;
    }
}
