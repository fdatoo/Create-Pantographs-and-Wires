package de.mrjulsen.paw.network;

import java.util.function.Supplier;

import de.mrjulsen.mcdragonlib.net.IPacketBase;
import de.mrjulsen.paw.event.ClientWrapper;
import de.mrjulsen.paw.traction.TrainSettings;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Server to client: a Traction Controller on a train was changed, so every client plays the train the same way. */
public class TrainSettingsSyncPacket implements IPacketBase<TrainSettingsSyncPacket> {
    private int entityId;
    private BlockPos localPos;
    private TrainSettings settings;

    public TrainSettingsSyncPacket() {}

    public TrainSettingsSyncPacket(int entityId, BlockPos localPos, TrainSettings settings) {
        this.entityId = entityId;
        this.localPos = localPos;
        this.settings = settings;
    }

    @Override
    public void encode(TrainSettingsSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId);
        buf.writeBlockPos(packet.localPos);
        packet.settings.writeTo(buf);
    }

    @Override
    public TrainSettingsSyncPacket decode(FriendlyByteBuf buf) {
        return new TrainSettingsSyncPacket(buf.readVarInt(), buf.readBlockPos(), TrainSettings.readFrom(buf));
    }

    @Override
    public void handle(TrainSettingsSyncPacket packet, Supplier<PacketContext> contextSupplier) {
        contextSupplier.get().queue(() -> ClientWrapper.applyTrainSettings(packet.entityId, packet.localPos, packet.settings));
    }
}
