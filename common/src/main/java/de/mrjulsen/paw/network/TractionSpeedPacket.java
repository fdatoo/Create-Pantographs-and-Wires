package de.mrjulsen.paw.network;

import java.util.UUID;
import java.util.function.Supplier;

import de.mrjulsen.mcdragonlib.net.IPacketBase;
import de.mrjulsen.paw.traction.TractionSpeedFeed;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.network.FriendlyByteBuf;

/** Server to client: a train's exact speed at a server tick, for the traction sound. */
public class TractionSpeedPacket implements IPacketBase<TractionSpeedPacket> {
    private UUID trainId;
    private long serverTick;
    private float speed;

    public TractionSpeedPacket() {}

    public TractionSpeedPacket(UUID trainId, long serverTick, float speed) {
        this.trainId = trainId;
        this.serverTick = serverTick;
        this.speed = speed;
    }

    @Override
    public void encode(TractionSpeedPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.trainId);
        buf.writeLong(packet.serverTick);
        buf.writeFloat(packet.speed);
    }

    @Override
    public TractionSpeedPacket decode(FriendlyByteBuf buf) {
        return new TractionSpeedPacket(buf.readUUID(), buf.readLong(), buf.readFloat());
    }

    @Override
    public void handle(TractionSpeedPacket packet, Supplier<PacketContext> contextSupplier) {
        contextSupplier.get().queue(() -> TractionSpeedFeed.offer(packet.trainId, packet.serverTick, packet.speed));
    }
}
