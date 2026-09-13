package de.mrjulsen.paw.blockentity.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import de.mrjulsen.paw.blockentity.ThirdRailBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;

/**
 * Draws a rail block's own piece of rail and every rail it is the primary end of, from geometry baked
 * when the rails last changed. Blocks with rails laid from them render off-screen like Create's track,
 * since a curve reaches far beyond the block that owns it; a lone rail block renders with its chunk.
 */
@Environment(EnvType.CLIENT)
public class ThirdRailRenderer extends SafeBlockEntityRenderer<ThirdRailBlockEntity> {

    public ThirdRailRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    protected void renderSafe(ThirdRailBlockEntity rail, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        Level level = rail.getLevel();
        if (level == null) {
            return;
        }
        long key = rail.renderKey();
        BakedRailMesh mesh = rail.renderCache instanceof BakedRailMesh baked && rail.renderCacheKey == key ? baked : null;
        if (mesh == null) {
            mesh = ThirdRailMesh.bake(rail);
            rail.renderCache = mesh;
            rail.renderCacheKey = key;
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(ThirdRailMesh.TEXTURE);
        mesh.render(level, ms.last(), buffer.getBuffer(RenderType.cutoutMipped()), sprite);
    }

    @Override
    public boolean shouldRenderOffScreen(ThirdRailBlockEntity rail) {
        return !rail.getConnections().isEmpty();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
