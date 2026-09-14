package de.mrjulsen.paw.network;

import java.util.function.Supplier;

import de.mrjulsen.mcdragonlib.net.IPacketBase;
import de.mrjulsen.paw.traction.TractionDebug;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.network.FriendlyByteBuf;

/** Server to client: switches the client's traction sound logging, for /paw_traction_debug client. */
public class TractionDebugPacket implements IPacketBase<TractionDebugPacket> {
    private boolean enabled;

    public TractionDebugPacket() {}

    public TractionDebugPacket(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void encode(TractionDebugPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enabled);
    }

    @Override
    public TractionDebugPacket decode(FriendlyByteBuf buf) {
        return new TractionDebugPacket(buf.readBoolean());
    }

    @Override
    public void handle(TractionDebugPacket packet, Supplier<PacketContext> contextSupplier) {
        contextSupplier.get().queue(() -> TractionDebug.setClient(packet.enabled));
    }
}
