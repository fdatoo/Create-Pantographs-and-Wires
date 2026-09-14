package de.mrjulsen.paw.client.sound;

import org.joml.Vector3f;

import de.mrjulsen.paw.traction.HeadRelativePosition;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Where a listener-relative traction voice sits. From outside the train it is placed at its world
 * position as seen from the camera, so it pans and fades like any positional sound. While the listener
 * is aboard it is drawn into the centre of the head over about half a second, since the motors are under
 * the floor all around.
 *
 * The voice has to be relative for this: Minecraft only sets a sound's relative flag when it starts,
 * so a positional voice could never be centred while playing.
 */
@Environment(EnvType.CLIENT)
final class ListenerRelativePlacement {
    private static final double ABOARD_BLEND_PER_TICK = 0.1;

    private final double[] look = new double[3];
    private final double[] up = new double[3];
    private final double[] left = new double[3];
    private final double[] out = new double[3];
    private double worldX;
    private double worldY;
    private double worldZ;
    private boolean aboard;
    private double aboardBlend;
    private boolean ticked;

    ListenerRelativePlacement(double x, double y, double z) {
        setWorldPosition(x, y, z);
    }

    void setWorldPosition(double x, double y, double z) {
        worldX = x;
        worldY = y;
        worldZ = z;
    }

    /** Before the voice's first tick the change is immediate, so a voice started aboard starts centred. */
    void setAboard(boolean aboard) {
        this.aboard = aboard;
        if (!ticked) {
            aboardBlend = aboard ? 1 : 0;
        }
    }

    void tick() {
        ticked = true;
        double target = aboard ? 1 : 0;
        aboardBlend += Math.max(-ABOARD_BLEND_PER_TICK, Math.min(ABOARD_BLEND_PER_TICK, target - aboardBlend));
    }

    /** The voice's relative position for the camera as it is now: {x, y, z}. */
    double[] relative() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 at = camera.getPosition();
        copy(camera.getLookVector(), look);
        copy(camera.getUpVector(), up);
        copy(camera.getLeftVector(), left);
        HeadRelativePosition.toListener(worldX - at.x, worldY - at.y, worldZ - at.z, look, up, left, 1 - aboardBlend, out);
        return out;
    }

    private static void copy(Vector3f from, double[] to) {
        to[0] = from.x();
        to[1] = from.y();
        to[2] = from.z();
    }
}
