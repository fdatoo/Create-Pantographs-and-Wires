package de.mrjulsen.paw.blockentity.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * A rail block's geometry, built once by ThirdRailMesh and replayed every frame. Texture coordinates
 * are kept in texels so a resource reload that moves the sprite needs no rebuild, and light is looked
 * up once per block the rail passes through rather than once per face.
 */
@Environment(EnvType.CLIENT)
final class BakedRailMesh {
    private final float[] positions;
    private final float[] normals;
    private final float[] texels;
    private final float[] shades;
    private final int[] lightSlots;
    private final long[] lightPositions;
    private final int[] light;

    BakedRailMesh(float[] positions, float[] normals, float[] texels, float[] shades, int[] lightSlots, long[] lightPositions) {
        this.positions = positions;
        this.normals = normals;
        this.texels = texels;
        this.shades = shades;
        this.lightSlots = lightSlots;
        this.lightPositions = lightPositions;
        this.light = new int[lightPositions.length];
    }

    void render(Level level, PoseStack.Pose pose, VertexConsumer consumer, TextureAtlasSprite sprite) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < lightPositions.length; i++) {
            light[i] = LevelRenderer.getLightColor(level, cursor.set(lightPositions[i]));
        }
        for (int quad = 0; quad < shades.length; quad++) {
            float shade = shades[quad];
            float nx = normals[quad * 3];
            float ny = normals[quad * 3 + 1];
            float nz = normals[quad * 3 + 2];
            int packedLight = light[lightSlots[quad]];
            for (int corner = 0; corner < 4; corner++) {
                int p = quad * 12 + corner * 3;
                int t = quad * 8 + corner * 2;
                consumer.vertex(pose.pose(), positions[p], positions[p + 1], positions[p + 2])
                    .color(shade, shade, shade, 1f)
                    .uv(sprite.getU(texels[t]), sprite.getV(texels[t + 1]))
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight)
                    .normal(pose.normal(), nx, ny, nz)
                    .endVertex();
            }
        }
    }
}
