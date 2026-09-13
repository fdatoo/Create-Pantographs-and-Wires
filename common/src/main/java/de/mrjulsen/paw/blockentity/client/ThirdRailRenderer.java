package de.mrjulsen.paw.blockentity.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import de.mrjulsen.paw.blockentity.ThirdRailBlockEntity;
import de.mrjulsen.paw.traction.ThirdRailConnection;
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
 * Draws a rail block's own piece of rail and every rail it is the primary end of. Rendered off-screen
 * like Create's track, since a curve reaches far beyond the block that owns it.
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
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(ThirdRailMesh.TEXTURE);
        ThirdRailMesh mesh = new ThirdRailMesh(level, rail.getBlockPos(), ms.last(), buffer.getBuffer(RenderType.cutoutMipped()), sprite);
        mesh.blockPiece(rail);
        for (ThirdRailConnection connection : rail.getConnections()) {
            if (connection.primary()) {
                mesh.connection(connection);
            }
        }
    }

    @Override
    public boolean shouldRenderOffScreen(ThirdRailBlockEntity rail) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
