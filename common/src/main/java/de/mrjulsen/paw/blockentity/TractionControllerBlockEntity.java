package de.mrjulsen.paw.blockentity;

import java.util.List;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import de.mrjulsen.paw.traction.TrainSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Holds a train's settings. On an assembled train the same data rides along as the block's saved data. */
public class TractionControllerBlockEntity extends SmartBlockEntity {
    private TrainSettings settings = TrainSettings.DEFAULT;

    public TractionControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    public TrainSettings settings() {
        return settings;
    }

    public void setSettings(TrainSettings settings) {
        this.settings = settings;
        notifyUpdate();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.put(TrainSettings.NBT_KEY, settings.write());
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        settings = TrainSettings.from(tag);
    }
}
