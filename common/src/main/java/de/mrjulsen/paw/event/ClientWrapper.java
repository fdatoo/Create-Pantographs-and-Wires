package de.mrjulsen.paw.event;

import de.mrjulsen.mcdragonlib.client.gui.DLScreen;
import de.mrjulsen.paw.client.gui.screens.CantileverSettingsScreen;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import de.mrjulsen.paw.traction.TrainSettings;
import de.mrjulsen.paw.network.TrainSettingsPacket;
import de.mrjulsen.paw.client.gui.screens.TractionControllerScreen;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import org.apache.commons.lang3.tuple.MutablePair;
import net.minecraft.world.item.ItemStack;

public class ClientWrapper {

    public static void showCantileverSettingsScreen(ItemStack stack) {
        DLScreen.setScreen(new CantileverSettingsScreen(stack));
    }

    public static void showTractionControllerScreen(TrainSettingsPacket.Target target, TrainSettings settings) {
        DLScreen.setScreen(new TractionControllerScreen(target, settings));
    }

    /** Applies a changed Traction Controller on a train to this client's copy of the train. */
    public static void applyTrainSettings(int entityId, BlockPos localPos, TrainSettings settings, long changed) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(entityId) instanceof AbstractContraptionEntity entity)) {
            return;
        }
        MutablePair<StructureBlockInfo, MovementContext> actor = entity.getContraption().getActorAt(localPos);
        if (actor != null && actor.right != null && actor.right.blockEntityData != null) {
            actor.right.blockEntityData.put(TrainSettings.NBT_KEY, settings.write(changed));
        }
    }
    
}
