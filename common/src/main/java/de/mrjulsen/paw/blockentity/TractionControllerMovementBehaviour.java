package de.mrjulsen.paw.blockentity;

import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import de.mrjulsen.paw.traction.TrainSettings;
import de.mrjulsen.paw.traction.TrainSettingsRegistry;

/**
 * A Traction Controller riding on a train: every client tick it tells the registry the train's settings,
 * which the traction sound, the steam sound and the nixie speedometers read.
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
        if (!context.world.isClientSide() || !(context.contraption.entity instanceof CarriageContraptionEntity carriage)
            || carriage.trainId == null) {
            return;
        }
        TrainSettingsRegistry.CLIENT.report(carriage.trainId, carriage.carriageIndex, context.localPos.asLong(),
            TrainSettings.from(context.blockEntityData), context.world.getGameTime());
    }
}
