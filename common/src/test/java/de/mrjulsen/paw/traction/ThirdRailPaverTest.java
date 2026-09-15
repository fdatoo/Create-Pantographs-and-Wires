package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class ThirdRailPaverTest {
    private static final BlockPos A = new BlockPos(2, 200, 2);
    private static final BlockPos B = new BlockPos(2, 200, 12);

    @Test
    void aStraightRailPavesEachBlockUnderItOnce() {
        ThirdRailConnection rail = new ThirdRailConnection(A, B, new Vec3(2.5, 200, 3), new Vec3(0, 0, 1), new Vec3(2.5, 200, 12), new Vec3(0, 0, -1), true, true, 5);
        Set<BlockPos> positions = ThirdRailPaver.positions(rail);
        assertEquals(11, positions.size());
        for (int z = 2; z <= 12; z++) {
            assertTrue(positions.contains(new BlockPos(2, 199, z)), "z " + z);
        }
    }

    @Test
    void aCurvedRailLeavesNoGapsUnderIt() {
        BlockPos end = new BlockPos(10, 200, 10);
        ThirdRailConnection curve = new ThirdRailConnection(A, end, new Vec3(2.5, 200, 3), new Vec3(0, 0, 1), new Vec3(10, 200, 10.5), new Vec3(-1, 0, 0), true, true, 7);
        List<BlockPos> positions = new ArrayList<>(ThirdRailPaver.positions(curve));
        assertTrue(positions.contains(A.below()));
        assertTrue(positions.contains(end.below()));
        for (int i = 1; i < positions.size() - 1; i++) {
            BlockPos a = positions.get(i - 1);
            BlockPos b = positions.get(i);
            assertEquals(199, b.getY());
            assertTrue(Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getZ() - b.getZ()) <= 1, a + " -> " + b);
        }
    }
}
