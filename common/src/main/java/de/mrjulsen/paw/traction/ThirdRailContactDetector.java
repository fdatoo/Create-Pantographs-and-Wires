package de.mrjulsen.paw.traction;

import org.joml.Vector3dc;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Finds whether a collector shoe touches any loaded third rail. */
public final class ThirdRailContactDetector {
    private ThirdRailContactDetector() {}

    public static boolean touches(Level level, Vector3dc centre, Vector3dc outward, Vector3dc up, Vector3dc forward) {
        double r = CollectorShoeContact.QUERY_RADIUS;
        AABB query = new AABB(centre.x() - r, centre.y() - r, centre.z() - r, centre.x() + r, centre.y() + r, centre.z() + r);
        return ThirdRailIndex.anyNear(level, query, rail -> !rail.isRemoved()
            && rail.anyConductorNear(query, conductor -> CollectorShoeContact.touches(centre, outward, up, forward, conductor)));
    }
}
