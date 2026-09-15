package de.mrjulsen.paw.blockentity;

import com.simibubi.create.AllMovementBehaviours;
import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.foundation.utility.Couple;

import de.mrjulsen.paw.mixin.create.NixieTubeBlockEntityAccessor;
import de.mrjulsen.paw.traction.SpeedReadout;
import de.mrjulsen.paw.traction.TractionSpeedFeed;
import de.mrjulsen.paw.traction.TractionSpeedSync;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

/**
 * Turns Create nixie tubes on a train into speedometers: each tube still showing its redstone reading
 * ("0 0", since nothing powers it on a moving train) shows the train's speed in whole metres per second.
 * Tubes given text with a name tag or clipboard keep it.
 *
 * The server sends the train's exact speed as it does for the traction sound, so trains without a
 * collector still report; the client falls back to the carriage's own movement if no report arrives.
 */
public class NixieSpeedometerMovementBehaviour implements MovementBehaviour {

    /** Attaches the speedometer to every colour of Create nixie tube. Safe to call before Create registers its blocks. */
    public static void register() {
        NixieSpeedometerMovementBehaviour behaviour = new NixieSpeedometerMovementBehaviour();
        for (DyeColor colour : DyeColor.values()) {
            String id = colour == DyeColor.ORANGE ? "nixie_tube" : colour.getSerializedName() + "_nixie_tube";
            AllMovementBehaviours.registerBehaviour(new ResourceLocation("create", id), behaviour);
        }
    }

    @Override
    public boolean renderAsNormalBlockEntity() {
        // Without this, Create leaves a block with a movement behaviour out of the contraption's rendered block entities.
        return true;
    }

    @Override
    public void tick(MovementContext context) {
        if (!(context.contraption.entity instanceof CarriageContraptionEntity carriage) || carriage.trainId == null) {
            return;
        }
        if (!carriage.level().isClientSide()) {
            TractionSpeedSync.tick(carriage);
            return;
        }
        if (!(context.contraption.presentBlockEntities.get(context.localPos) instanceof NixieTubeBlockEntity nixie)
            || !nixie.reactsToRedstone()) {
            return;
        }
        SpeedReadout readout;
        if (context.temporaryData instanceof SpeedReadout existing) {
            readout = existing;
        } else {
            readout = new SpeedReadout();
            context.temporaryData = readout;
        }
        double blocksPerTick = TractionSpeedFeed.reported(carriage.trainId)
            .map(TractionSpeedFeed.Report::speed)
            .orElseGet(() -> context.motion.length());
        readout.update(blocksPerTick * 20);
        ((NixieTubeBlockEntityAccessor) nixie).paw$setDisplayedStrings(Couple.create(readout.tens(), readout.ones()));
    }
}
