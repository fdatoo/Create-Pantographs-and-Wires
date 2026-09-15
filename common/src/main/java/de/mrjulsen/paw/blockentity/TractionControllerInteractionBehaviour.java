package de.mrjulsen.paw.blockentity;

import org.apache.commons.lang3.tuple.MutablePair;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.behaviour.MovingInteractionBehaviour;

import de.mrjulsen.paw.event.ClientWrapper;
import de.mrjulsen.paw.network.TrainSettingsPacket;
import de.mrjulsen.paw.traction.TrainSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Right-clicking a Traction Controller on an assembled train opens its settings, even while the train moves. */
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
            ClientWrapper.showTractionControllerScreen(TrainSettingsPacket.Target.onTrain(contraptionEntity.getId(), localPos),
                TrainSettings.from(actor.right.blockEntityData));
        }
        return true;
    }
}
