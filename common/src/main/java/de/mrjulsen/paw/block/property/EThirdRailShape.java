package de.mrjulsen.paw.block.property;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

/**
 * Which way a third rail block runs. Like Create track, rails run along either horizontal axis or
 * along a diagonal; a diagonal axis is left unnormalised so half of it reaches the block's corner.
 */
public enum EThirdRailShape implements StringRepresentable {
    Z("z", 0, 1),
    X("x", 1, 0),
    /** Runs from north-west to south-east. */
    PD("pd", 1, 1),
    /** Runs from north-east to south-west. */
    ND("nd", -1, 1);

    private final String name;
    private final int axisX;
    private final int axisZ;

    EThirdRailShape(String name, int axisX, int axisZ) {
        this.name = name;
        this.axisX = axisX;
        this.axisZ = axisZ;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public Vec3 axis() {
        return new Vec3(axisX, 0, axisZ);
    }

    public boolean isDiagonal() {
        return axisX != 0 && axisZ != 0;
    }

    /** The shape running along a horizontal direction, either way round. */
    public static EThirdRailShape fromAxis(double x, double z) {
        if (Math.abs(x) < 1e-6) {
            return Z;
        }
        if (Math.abs(z) < 1e-6) {
            return X;
        }
        return (x > 0) == (z > 0) ? PD : ND;
    }

    /** The shape closest to a horizontal look direction, snapped to eighths of a turn. */
    public static EThirdRailShape fromLook(Vec3 look) {
        int octant = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(look.z, look.x)) / 45.0), 8);
        // 0 east, 1 south-east, 2 south, 3 south-west, 4 west, 5 north-west, 6 north, 7 north-east
        return switch (octant) {
            case 0, 4 -> X;
            case 2, 6 -> Z;
            case 1, 5 -> PD;
            default -> ND;
        };
    }

    public EThirdRailShape rotate(Rotation rotation) {
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            return switch (this) {
                case Z -> X;
                case X -> Z;
                case PD -> ND;
                case ND -> PD;
            };
        }
        return this;
    }

    public EThirdRailShape mirror(Mirror mirror) {
        if (mirror == Mirror.NONE || !isDiagonal()) {
            return this;
        }
        return this == PD ? ND : PD;
    }
}
