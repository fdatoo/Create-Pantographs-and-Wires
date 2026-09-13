package de.mrjulsen.paw.blockentity.client;

import org.joml.Vector3d;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.blockentity.ThirdRailBlockEntity;
import de.mrjulsen.paw.traction.ThirdRailConnection;
import de.mrjulsen.paw.traction.ThirdRailCurve;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a third rail's geometry. A rail is a fixed cross-section swept along its centreline: the
 * steel conductor, and above it the cover board, with a brown insulator pedestal under the conductor
 * and a treated metal support holding up the cover every two blocks. At a rail block with no rail
 * leaving one side, the conductor and cover dip down into an end ramp, which is how shoes ride on.
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

    private final Level level;
    private final BlockPos origin;
    private final PoseStack.Pose pose;
    private final VertexConsumer consumer;
    private final TextureAtlasSprite sprite;

    ThirdRailMesh(Level level, BlockPos origin, PoseStack.Pose pose, VertexConsumer consumer, TextureAtlasSprite sprite) {
        this.level = level;
        this.origin = origin;
        this.pose = pose;
        this.consumer = consumer;
        this.sprite = sprite;
    }

    /** The straight piece of rail running through a rail block, with end ramps where no rail leaves. */
    void blockPiece(ThirdRailBlockEntity rail) {
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

    /** A laid rail between two rail blocks. */
    void connection(ThirdRailConnection connection) {
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
        float u0 = sprite.getU(0);
        float u1 = sprite.getU(Math.max(1, Math.min(16, texelsU)));
        float v0 = sprite.getV(band * BAND_ROWS);
        float v1 = sprite.getV(band * BAND_ROWS + Math.max(1, Math.min(BAND_ROWS, texelsV)));

        Vector3d winding = new Vector3d(c1).sub(c0).cross(new Vector3d(c2).sub(c0));
        Vector3d centre = new Vector3d(c0).add(c2).mul(0.5);
        int light = LevelRenderer.getLightColor(level, BlockPos.containing(origin.getX() + centre.x, origin.getY() + centre.y + 0.05, origin.getZ() + centre.z));
        float shade = shade(normal);

        if (winding.dot(normal) >= 0) {
            vertex(c0, normal, u0, v0, light, shade);
            vertex(c1, normal, u1, v0, light, shade);
            vertex(c2, normal, u1, v1, light, shade);
            vertex(c3, normal, u0, v1, light, shade);
        } else {
            vertex(c0, normal, u0, v0, light, shade);
            vertex(c3, normal, u0, v1, light, shade);
            vertex(c2, normal, u1, v1, light, shade);
            vertex(c1, normal, u1, v0, light, shade);
        }
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

    private void vertex(Vector3d p, Vector3d n, float u, float v, int light, float shade) {
        consumer.vertex(pose.pose(), (float) p.x, (float) p.y, (float) p.z)
            .color(shade, shade, shade, 1f)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(pose.normal(), (float) n.x, (float) n.y, (float) n.z)
            .endVertex();
    }
}
