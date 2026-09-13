package de.mrjulsen.paw.client;

import com.simibubi.create.CreateClient;

import de.mrjulsen.paw.item.ThirdRailItem;
import de.mrjulsen.paw.traction.ThirdRailPlacement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * While a rail start is selected, shows where the rail would go and whether it can: the conductor's
 * path as green or red outline lines, and Create's track placement message in the action bar.
 */
@Environment(EnvType.CLIENT)
public final class ThirdRailPlacementPreview {
    private static final int VALID_COLOUR = 0x95CD41;
    private static final int INVALID_COLOUR = 0xEA5C2B;

    private record Slot(int index) {}

    private static int shownLines;

    private ThirdRailPlacementPreview() {}

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        int lines = 0;

        if (player != null && mc.level != null
            && player.getMainHandItem().getItem() instanceof ThirdRailItem item
            && ThirdRailPlacement.hasSelection(player.getMainHandItem())
            && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
        ) {
            ItemStack stack = player.getMainHandItem();
            ThirdRailPlacement.Target target = ThirdRailPlacement.target(mc.level, new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit), hit.getBlockPos(), item);
            if (target != null) {
                ThirdRailPlacement.Attempt attempt = ThirdRailPlacement.tryConnect(mc.level, player, stack, target);
                if (attempt.message() != null) {
                    player.displayClientMessage(attempt.message(), true);
                }
                if (attempt.connection() != null) {
                    double[] c = attempt.connection().conductor();
                    int colour = attempt.valid() ? VALID_COLOUR : INVALID_COLOUR;
                    for (int i = 3; i + 2 < c.length; i += 3) {
                        CreateClient.OUTLINER.showLine(new Slot(lines), new Vec3(c[i - 3], c[i - 2], c[i - 1]), new Vec3(c[i], c[i + 1], c[i + 2]))
                            .colored(colour)
                            .lineWidth(1 / 16f);
                        lines++;
                    }
                }
            }
        }

        for (int i = lines; i < shownLines; i++) {
            CreateClient.OUTLINER.remove(new Slot(i));
        }
        shownLines = lines;
    }
}
