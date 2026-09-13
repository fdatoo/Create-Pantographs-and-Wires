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
    void railReadAtAnotherOwnerIsRejected() {
        // The same data loaded into a block that has been carried somewhere else.
        assertNull(ThirdRailConnection.read(A.offset(0, 0, -5), straight(7).write()));
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

    private static ListTag vec(double x, double y, double z) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(x));
        list.add(DoubleTag.valueOf(y));
        list.add(DoubleTag.valueOf(z));
        return list;
    }
}
