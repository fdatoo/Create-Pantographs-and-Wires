package de.mrjulsen.paw.blockentity;

import org.apache.commons.lang3.tuple.MutablePair;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.behaviour.MovingInteractionBehaviour;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import de.mrjulsen.paw.event.ClientWrapper;
import de.mrjulsen.paw.network.TrainSettingsPacket;
import de.mrjulsen.paw.traction.TrainSettings;
import de.mrjulsen.paw.traction.TrainSettingsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/**
 * Right-clicking a Traction Controller on an assembled train opens the train's settings, even while the
 * train moves. It shows the train's current settings, which may have been chosen on another controller.
 */
public class TractionControllerInteractionBehaviour extends MovingInteractionBehaviour {

    @Override
    public boolean handlePlayerInteraction(Player player, InteractionHand activeHand, BlockPos localPos, AbstractContraptionEntity contraptionEntity) {
        if (player.isShiftKeyDown()) {
            return false;
        }
        MutablePair<StructureBlockInfo, MovementContext> actor = contraptionEntity.getContraption().getActorAt(localPos);
        if (actor == null || actor.right == null) {
            return false;
        }
        if (player.level().isClientSide()) {
            TrainSettings shown = TrainSettings.from(actor.right.blockEntityData);
            if (contraptionEntity instanceof CarriageContraptionEntity carriage && carriage.trainId != null) {
                shown = TrainSettingsRegistry.CLIENT.find(carriage.trainId, player.level().getGameTime())
                    .map(TrainSettingsRegistry.Stamped::settings)
                    .orElse(shown);
            }
            ClientWrapper.showTractionControllerScreen(TrainSettingsPacket.Target.onTrain(contraptionEntity.getId(), localPos), shown);
        }
        return true;
    }
}
