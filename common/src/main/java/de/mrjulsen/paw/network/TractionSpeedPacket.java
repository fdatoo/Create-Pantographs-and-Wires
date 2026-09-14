package de.mrjulsen.paw.network;

import java.util.UUID;
import java.util.function.Supplier;

import de.mrjulsen.mcdragonlib.net.IPacketBase;
import de.mrjulsen.paw.traction.TractionSpeedFeed;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server to client: a train's exact speed and grade at a server tick, and whether its driver holds a
 * direction, for the traction sound.
 */
public class TractionSpeedPacket implements IPacketBase<TractionSpeedPacket> {
    private UUID trainId;
    private long serverTick;
    private float speed;
    private float grade;
    private boolean throttleHeld;

    public TractionSpeedPacket() {}

    public TractionSpeedPacket(UUID trainId, long serverTick, float speed, float grade, boolean throttleHeld) {
        this.trainId = trainId;
        this.serverTick = serverTick;
        this.speed = speed;
        this.grade = grade;
        this.throttleHeld = throttleHeld;
    }

    @Override
    public void encode(TractionSpeedPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.trainId);
        buf.writeLong(packet.serverTick);
        buf.writeFloat(packet.speed);
        buf.writeFloat(packet.grade);
        buf.writeBoolean(packet.throttleHeld);
    }

    @Override
    public TractionSpeedPacket decode(FriendlyByteBuf buf) {
        return new TractionSpeedPacket(buf.readUUID(), buf.readLong(), buf.readFloat(), buf.readFloat(), buf.readBoolean());
    }

    @Override
    public void handle(TractionSpeedPacket packet, Supplier<PacketContext> contextSupplier) {
        contextSupplier.get().queue(() -> TractionSpeedFeed.offer(packet.trainId, packet.serverTick, packet.speed, packet.grade, packet.throttleHeld));
    }
}
