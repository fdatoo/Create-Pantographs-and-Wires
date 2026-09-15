package de.mrjulsen.paw.blockentity;

import java.util.Map;

import com.simibubi.create.AllMovementBehaviours;
import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.redstone.nixieTube.DoubleFaceAttachedBlock;
import com.simibubi.create.content.redstone.nixieTube.DoubleFaceAttachedBlock.DoubleAttachFace;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlock;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.foundation.utility.Couple;

import de.mrjulsen.paw.mixin.create.NixieTubeBlockEntityAccessor;
import de.mrjulsen.paw.traction.SpeedReadout;
import de.mrjulsen.paw.traction.TractionSpeedFeed;
import de.mrjulsen.paw.traction.TractionSpeedSync;
import de.mrjulsen.paw.traction.TrainSettings;
import de.mrjulsen.paw.traction.TrainSettingsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/**
 * Turns Create nixie tubes on a train into speedometers: each tube still showing its redstone reading
 * ("0 0", since nothing powers it on a moving train) shows the train's speed, in the unit set on the
 * train's Traction Controller (m/s without one). A row of tubes shows the number right-aligned across
 * the row, so two tubes can show 144 km/h. Tubes given text with a name tag or clipboard keep it.
 *
 * The server sends the train's exact speed as it does for the traction sound, so trains without a
 * collector still report; the client falls back to the carriage's own movement if no report arrives.
 */
public class NixieSpeedometerMovementBehaviour implements MovementBehaviour {
    /** Longest row read as one readout, so a long wall of tubes isn't walked every tick. */
    private static final int MAX_ROW = 4;

    private record Row(int index, int length) {}

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
        TrainSettings.SpeedUnit unit = TrainSettingsRegistry.CLIENT.of(carriage.trainId, carriage.level().getGameTime()).speedUnit();
        double blocksPerTick = TractionSpeedFeed.reported(carriage.trainId)
            .map(TractionSpeedFeed.Report::speed)
            .orElseGet(() -> context.motion.length());
        Row row = row(context.contraption.getBlocks(), context.localPos, context.state);
        readout.update(unit.fromMetresPerSecond(blocksPerTick * 20), SpeedReadout.maxFor(row.length()));
        String text = readout.text(row.length() * 2);
        int at = row.index() * 2;
        ((NixieTubeBlockEntityAccessor) nixie).paw$setDisplayedStrings(Couple.create(text.substring(at, at + 1), text.substring(at + 1, at + 2)));
    }

    /** Where this tube sits in its row of matching tubes on the train, walked the same way Create's own nixie rows are. */
    private static Row row(Map<BlockPos, StructureBlockInfo> blocks, BlockPos pos, BlockState state) {
        Direction left = state.getValue(NixieTubeBlock.FACING).getOpposite();
        DoubleAttachFace face = state.getValue(DoubleFaceAttachedBlock.FACE);
        if (face == DoubleAttachFace.WALL) {
            left = Direction.UP;
        } else if (face == DoubleAttachFace.WALL_REVERSED) {
            left = Direction.DOWN;
        }
        int index = 0;
        BlockPos cursor = pos;
        while (index < MAX_ROW - 1 && matches(blocks, cursor.relative(left), state)) {
            cursor = cursor.relative(left);
            index++;
        }
        int length = index + 1;
        cursor = pos;
        while (length < MAX_ROW && matches(blocks, cursor.relative(left.getOpposite()), state)) {
            cursor = cursor.relative(left.getOpposite());
            length++;
        }
        return new Row(index, length);
    }

    private static boolean matches(Map<BlockPos, StructureBlockInfo> blocks, BlockPos pos, BlockState state) {
        StructureBlockInfo info = blocks.get(pos);
        return info != null && NixieTubeBlock.areNixieBlocksEqual(info.state(), state);
    }
}
