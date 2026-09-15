package de.mrjulsen.paw.blockentity;

import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.network.TrainSettingsSyncPacket;
import de.mrjulsen.paw.traction.TrainSettings;
import de.mrjulsen.paw.traction.TrainSettingsRegistry;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerLevel;

/**
 * A Traction Controller riding on a train. Every tick it reports its settings: on the client for the
 * traction sound, steam sound and nixie speedometers, and on the server so that a controller holding older
 * settings than another on the same train takes the newer ones. That keeps every controller on a train
 * showing the same settings, including one on a carriage that was unloaded when they changed and one added
 * to the train later.
 */
public class TractionControllerMovementBehaviour implements MovementBehaviour {

    @Override
    public boolean renderAsNormalBlockEntity() {
        return true;
    }

    @Override
    public boolean mustTickWhileDisabled() {
        return true;
    }

    @Override
    public void tick(MovementContext context) {
        if (!(context.contraption.entity instanceof CarriageContraptionEntity carriage) || carriage.trainId == null
            || context.blockEntityData == null) {
            return;
        }
        long tick = context.world.getGameTime();
        TrainSettingsRegistry.Stamped own = new TrainSettingsRegistry.Stamped(
            TrainSettings.from(context.blockEntityData), TrainSettings.changedAt(context.blockEntityData));
        if (context.world.isClientSide()) {
            TrainSettingsRegistry.CLIENT.report(carriage.trainId, carriage.carriageIndex, context.localPos.asLong(), own, tick);
            return;
        }
        if (!(context.world instanceof ServerLevel level)) {
            return;
        }
        TrainSettingsRegistry registry = TrainSettingsRegistry.SERVER;
        registry.report(carriage.trainId, carriage.carriageIndex, context.localPos.asLong(), own, tick);
        registry.find(carriage.trainId, tick)
            .filter(newest -> newest.changed() > own.changed())
            .ifPresent(newest -> {
                context.blockEntityData.put(TrainSettings.NBT_KEY, newest.settings().write(newest.changed()));
                registry.report(carriage.trainId, carriage.carriageIndex, context.localPos.asLong(), newest, tick);
                level.getChunkSource().broadcast(carriage, PantographsAndWires.net().CHANNEL.toPacket(NetworkManager.Side.S2C,
                    new TrainSettingsSyncPacket(carriage.getId(), context.localPos, newest.settings(), newest.changed())));
            });
    }
}
