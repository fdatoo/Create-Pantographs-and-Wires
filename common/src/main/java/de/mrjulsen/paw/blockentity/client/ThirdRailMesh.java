package de.mrjulsen.paw.blockentity.client;

import org.joml.Vector3d;

import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.blockentity.ThirdRailBlockEntity;
import de.mrjulsen.paw.traction.ThirdRailConnection;
import de.mrjulsen.paw.traction.ThirdRailCurve;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a third rail's geometry once, into a BakedRailMesh the renderer replays every frame. A rail
 * is a fixed cross-section swept along its centreline: the steel conductor, and above it the cover
 * board, with a brown insulator pedestal under the conductor and a treated metal support holding up
 * the cover every two blocks. At a rail block with no rail leaving one side, the conductor and cover
 * dip down into an end ramp, which is how shoes ride on.
 */
@Environment(EnvType.CLIENT)
final class ThirdRailMesh {
    static final ResourceLocation TEXTURE = new ResourceLocation(PantographsAndWires.MOD_ID, "block/third_rail");

    private static final double PX = 1 / 16d;
    /** Longest straight piece a curve is drawn with. */
    private static final double PIECE_LENGTH = 0.25;
    private static final double SUPPORT_SPACING = 2;
    private static final int PIECE_STEPS = 4;
    /** Rows per material in third_rail.png: conductor, cover, insulator, support. */
    private static final int BAND_ROWS = 4;

    /**
     * A box in the rail's cross-section, in pixels: sideways extent (positive is right of the direction
     * of travel), height above the centreline, how far it dips at an end ramp, and its texture band.
     */
    private record Profile(double right0, double right1, double up0, double up1, double rampDrop, int band) {
        Profile mirrored(double side) {
            return side > 0 ? this : new Profile(-right1, -right0, up0, up1, rampDrop, band);
        }
    }

    private static final Profile CONDUCTOR = new Profile(-1.5, 1.5, 3, 6, 3, 0);
    private static final Profile COVER = new Profile(-4, 4, 9, 10.5, 6, 1);
    private static final Profile PEDESTAL = new Profile(-2, 2, 0, 3, 0, 2);
    private static final double PEDESTAL_HALF_LENGTH = 2;
    private static final Profile SUPPORT = new Profile(3.5, 4.5, 3, 9, 0, 3);
    private static final double SUPPORT_HALF_LENGTH = 1;

    /** A point on the centreline with its orientation; ramp is 0 on the running rail and 1 at the tip of an end ramp. */
    private record Frame(Vector3d pos, Vector3d tangent, Vector3d right, Vector3d up, double ramp) {}

    private final BlockPos origin;
    private final FloatArrayList positions = new FloatArrayList();
    private final FloatArrayList normals = new FloatArrayList();
    private final FloatArrayList texels = new FloatArrayList();
    private final FloatArrayList shades = new FloatArrayList();
    private final IntArrayList lightSlots = new IntArrayList();
    private final LongArrayList lightPositions = new LongArrayList();
    private final Long2IntOpenHashMap lightSlotByPosition = new Long2IntOpenHashMap();

    private ThirdRailMesh(BlockPos origin) {
        this.origin = origin;
        lightSlotByPosition.defaultReturnValue(-1);
    }

    /** Everything a rail block draws: its own piece and every rail it is the primary end of. */
    static BakedRailMesh bake(ThirdRailBlockEntity rail) {
        ThirdRailMesh mesh = new ThirdRailMesh(rail.getBlockPos());
        mesh.blockPiece(rail);
        for (ThirdRailConnection connection : rail.getConnections()) {
            if (connection.primary()) {
                mesh.connection(connection);
            }
        }
        return new BakedRailMesh(
            positions(mesh), mesh.normals.toFloatArray(), mesh.texels.toFloatArray(), mesh.shades.toFloatArray(),
            mesh.lightSlots.toIntArray(), mesh.lightPositions.toLongArray()
        );
    }

    private static float[] positions(ThirdRailMesh mesh) {
        return mesh.positions.toFloatArray();
    }

    private void blockPiece(ThirdRailBlockEntity rail) {
        Vec3 axis = rail.shape().axis();
        boolean forward = false;
        boolean backward = false;
        for (ThirdRailConnection connection : rail.getConnections()) {
            double direction = connection.axis1().dot(axis);
            forward |= direction > 0;
            backward |= direction < 0;
        }

        Vector3d tangent = new Vector3d(axis.x, axis.y, axis.z).normalize();
        Frame[] frames = new Frame[PIECE_STEPS + 1];
        for (int i = 0; i <= PIECE_STEPS; i++) {
            double u = -1 + 2.0 * i / PIECE_STEPS;
            Vector3d pos = new Vector3d(0.5 + axis.x * 0.5 * u, 0, 0.5 + axis.z * 0.5 * u);
            double ramp = u < 0 ? (backward ? 0 : -u) : (forward ? 0 : u);
            frames[i] = frame(pos, tangent, ramp);
        }
        sweep(frames, CONDUCTOR);
        sweep(frames, COVER);
        supports(frames[PIECE_STEPS / 2], rail.pieceSupportsOnRight() ? 1 : -1);
    }

    private void connection(ThirdRailConnection connection) {
        ThirdRailCurve curve = connection.curve();
        double[] parameters = curve.evenParameters(curve.segmentCount(PIECE_LENGTH));
        Frame[] frames = new Frame[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            frames[i] = frameOnCurve(curve, parameters[i]);
        }
        sweep(frames, CONDUCTOR);
        sweep(frames, COVER);

        double side = connection.supportsOnRight() ? 1 : -1;
        for (double distance = SUPPORT_SPACING - 0.5; distance < curve.length() - 0.5; distance += SUPPORT_SPACING) {
            supports(frameOnCurve(curve, curve.parameterAtDistance(distance)), side);
        }
    }

    private Frame frameOnCurve(ThirdRailCurve curve, double t) {
        Vector3d pos = curve.position(t, new Vector3d()).sub(origin.getX(), origin.getY(), origin.getZ());
        Vector3d tangent = curve.derivative(t, new Vector3d());
        if (tangent.lengthSquared() < 1e-12) {
            tangent.set(0, 0, 1);
        }
        return frame(pos, tangent.normalize(), 0);
    }

    private static Frame frame(Vector3d pos, Vector3d tangent, double ramp) {
        Vector3d right = new Vector3d(tangent).cross(0, 1, 0);
        if (right.lengthSquared() < 1e-12) {
            right.set(1, 0, 0);
        }
        right.normalize();
        Vector3d up = new Vector3d(right).cross(tangent).normalize();
        return new Frame(pos, tangent, right, up, ramp);
    }

    private void supports(Frame at, double side) {
        box(at, PEDESTAL, PEDESTAL_HALF_LENGTH);
        box(at, SUPPORT.mirrored(side), SUPPORT_HALF_LENGTH);
    }

    /** A short straight box centred on a frame, halfLength pixels either side along the tangent. */
    private void box(Frame at, Profile profile, double halfLength) {
        Vector3d offset = new Vector3d(at.tangent()).mul(halfLength * PX);
        Frame start = new Frame(new Vector3d(at.pos()).sub(offset), at.tangent(), at.right(), at.up(), 0);
        Frame end = new Frame(new Vector3d(at.pos()).add(offset), at.tangent(), at.right(), at.up(), 0);
        sweep(new Frame[] {start, end}, profile);
    }

    /** Sweeps a profile box along consecutive frames, capping both ends. */
    private void sweep(Frame[] frames, Profile p) {
        double width = p.right1() - p.right0();
        double height = p.up1() - p.up0();
        for (int i = 0; i + 1 < frames.length; i++) {
            Frame a = frames[i];
            Frame b = frames[i + 1];
            double length = Math.min(16, a.pos().distance(b.pos()) * 16);

            Vector3d aLeftTop = corner(a, p, p.right0(), p.up1());
            Vector3d aRightTop = corner(a, p, p.right1(), p.up1());
            Vector3d aLeftBottom = corner(a, p, p.right0(), p.up0());
            Vector3d aRightBottom = corner(a, p, p.right1(), p.up0());
            Vector3d bLeftTop = corner(b, p, p.right0(), p.up1());
            Vector3d bRightTop = corner(b, p, p.right1(), p.up1());
            Vector3d bLeftBottom = corner(b, p, p.right0(), p.up0());
            Vector3d bRightBottom = corner(b, p, p.right1(), p.up0());

            Vector3d up = average(a.up(), b.up());
            Vector3d right = average(a.right(), b.right());
            quad(aLeftTop, aRightTop, bRightTop, bLeftTop, up, p.band(), length, width);
            quad(aLeftBottom, aRightBottom, bRightBottom, bLeftBottom, up.negate(new Vector3d()), p.band(), length, width);
            quad(aRightBottom, aRightTop, bRightTop, bRightBottom, right, p.band(), length, height);
            quad(aLeftBottom, aLeftTop, bLeftTop, bLeftBottom, right.negate(new Vector3d()), p.band(), length, height);

            if (i == 0) {
                quad(aLeftBottom, aRightBottom, aRightTop, aLeftTop, a.tangent().negate(new Vector3d()), p.band(), width, height);
            }
            if (i + 2 == frames.length) {
                quad(bLeftBottom, bRightBottom, bRightTop, bLeftTop, b.tangent(), p.band(), width, height);
            }
        }
    }

    private static Vector3d corner(Frame f, Profile p, double right, double up) {
        double lift = (up - p.rampDrop() * f.ramp()) * PX;
        return new Vector3d(f.pos())
            .add(f.right().x * right * PX, f.right().y * right * PX, f.right().z * right * PX)
            .add(f.up().x * lift, f.up().y * lift, f.up().z * lift);
    }

    private static Vector3d average(Vector3d a, Vector3d b) {
        Vector3d sum = new Vector3d(a).add(b);
        return sum.lengthSquared() < 1e-12 ? new Vector3d(a) : sum.normalize();
    }

    /**
     * One textured face. Corners go round the face in order; they are wound to face along the given
     * normal, so back-face culling keeps the outside.
     */
    private void quad(Vector3d c0, Vector3d c1, Vector3d c2, Vector3d c3, Vector3d normal, int band, double texelsU, double texelsV) {
        float u1 = (float) Math.max(1, Math.min(16, texelsU));
        float v0 = band * BAND_ROWS;
        float v1 = v0 + (float) Math.max(1, Math.min(BAND_ROWS, texelsV));

        Vector3d winding = new Vector3d(c1).sub(c0).cross(new Vector3d(c2).sub(c0));
        if (winding.dot(normal) >= 0) {
            corner(c0, 0, v0);
            corner(c1, u1, v0);
            corner(c2, u1, v1);
            corner(c3, 0, v1);
        } else {
            corner(c0, 0, v0);
            corner(c3, 0, v1);
            corner(c2, u1, v1);
            corner(c1, u1, v0);
        }
        normals.add((float) normal.x);
        normals.add((float) normal.y);
        normals.add((float) normal.z);
        shades.add(shade(normal));

        Vector3d centre = new Vector3d(c0).add(c2).mul(0.5);
        long light = BlockPos.asLong(
            (int) Math.floor(origin.getX() + centre.x),
            (int) Math.floor(origin.getY() + centre.y + 0.05),
            (int) Math.floor(origin.getZ() + centre.z)
        );
        int slot = lightSlotByPosition.get(light);
        if (slot < 0) {
            slot = lightPositions.size();
            lightPositions.add(light);
            lightSlotByPosition.put(light, slot);
        }
        lightSlots.add(slot);
    }

    private void corner(Vector3d p, float u, float v) {
        positions.add((float) p.x);
        positions.add((float) p.y);
        positions.add((float) p.z);
        texels.add(u);
        texels.add(v);
    }

    /** Vanilla's directional block shading, since the block shaders don't light by normal. */
    private static float shade(Vector3d n) {
        double ax = Math.abs(n.x);
        double ay = Math.abs(n.y);
        double az = Math.abs(n.z);
        if (ay >= ax && ay >= az) {
            return n.y > 0 ? 1.0f : 0.5f;
        }
        return (float) (0.6 + 0.2 * az / (ax + az));
    }
}
