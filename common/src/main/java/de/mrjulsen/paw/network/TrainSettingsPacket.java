package de.mrjulsen.paw.network;

import java.util.function.Supplier;

import org.apache.commons.lang3.tuple.MutablePair;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import de.mrjulsen.mcdragonlib.net.IPacketBase;
import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.block.TractionControllerBlock;
import de.mrjulsen.paw.blockentity.TractionControllerBlockEntity;
import de.mrjulsen.paw.traction.TrainSettings;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/**
 * Client to server: new settings from a Traction Controller's screen. The controller is either a block
 * in the world or one riding on a train; on a train the settings are written into the block's saved data,
 * so they stay when the train is disassembled, and sent to everyone tracking the train.
 */
public class TrainSettingsPacket implements IPacketBase<TrainSettingsPacket> {
    /** How close a player must be to the controller they change. */
    private static final double REACH = 8;

    /** Which controller: a block at a world position, or one at a local position on a train entity. */
    public record Target(boolean onTrain, int entityId, BlockPos pos) {
        public static Target block(BlockPos pos) {
            return new Target(false, -1, pos);
        }

        public static Target onTrain(int entityId, BlockPos localPos) {
            return new Target(true, entityId, localPos);
        }
    }

    private Target target;
    private TrainSettings settings;

    public TrainSettingsPacket() {}

    public TrainSettingsPacket(Target target, TrainSettings settings) {
        this.target = target;
        this.settings = settings;
    }

    @Override
    public void encode(TrainSettingsPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.target.onTrain());
        buf.writeVarInt(packet.target.entityId());
        buf.writeBlockPos(packet.target.pos());
        packet.settings.writeTo(buf);
    }

    @Override
    public TrainSettingsPacket decode(FriendlyByteBuf buf) {
        Target target = new Target(buf.readBoolean(), buf.readVarInt(), buf.readBlockPos());
        return new TrainSettingsPacket(target, TrainSettings.readFrom(buf));
    }

    @Override
    public void handle(TrainSettingsPacket packet, Supplier<PacketContext> contextSupplier) {
        PacketContext context = contextSupplier.get();
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            Target target = packet.target;
            if (!target.onTrain()) {
                if (player.distanceToSqr(Vec3.atCenterOf(target.pos())) <= REACH * REACH
                    && level.getBlockEntity(target.pos()) instanceof TractionControllerBlockEntity controller) {
                    controller.setSettings(packet.settings);
                }
                return;
            }
            if (!(level.getEntity(target.entityId()) instanceof AbstractContraptionEntity entity)) {
                return;
            }
            MutablePair<StructureBlockInfo, MovementContext> actor = entity.getContraption().getActorAt(target.pos());
            if (actor == null || actor.right == null || actor.right.blockEntityData == null
                || !(actor.left.state().getBlock() instanceof TractionControllerBlock)
                || player.distanceToSqr(entity.toGlobalVector(Vec3.atCenterOf(target.pos()), 1)) > REACH * REACH) {
                return;
            }
            actor.right.blockEntityData.put(TrainSettings.NBT_KEY, packet.settings.write());
            level.getChunkSource().broadcast(entity, PantographsAndWires.net().CHANNEL.toPacket(NetworkManager.Side.S2C,
                new TrainSettingsSyncPacket(entity.getId(), target.pos(), packet.settings)));
        });
    }
}
