package de.mrjulsen.paw.network;

import java.util.function.Supplier;

import de.mrjulsen.mcdragonlib.net.IPacketBase;
import de.mrjulsen.paw.item.ThirdRailItem;
import de.mrjulsen.paw.traction.ThirdRailPlacement;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Client to server: the sprint key is held for the third rail click that follows, so the curve takes up
 * all the room. Sent from the item's client-side use, which runs before the click itself is sent, the way
 * Create's PlaceExtendedCurvePacket works for track.
 */
public class ThirdRailMaximisePacket implements IPacketBase<ThirdRailMaximisePacket> {
    private boolean mainHand;

    public ThirdRailMaximisePacket() {}

    public ThirdRailMaximisePacket(boolean mainHand) {
        this.mainHand = mainHand;
    }

    @Override
    public void encode(ThirdRailMaximisePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.mainHand);
    }

    @Override
    public ThirdRailMaximisePacket decode(FriendlyByteBuf buf) {
        return new ThirdRailMaximisePacket(buf.readBoolean());
    }

    @Override
    public void handle(ThirdRailMaximisePacket packet, Supplier<PacketContext> contextSupplier) {
        PacketContext context = contextSupplier.get();
        context.queue(() -> {
            if (context.getPlayer() == null) {
                return;
            }
            ItemStack stack = context.getPlayer().getItemInHand(packet.mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            if (stack.getItem() instanceof ThirdRailItem) {
                ThirdRailPlacement.markMaximise(stack);
            }
        });
    }
}
