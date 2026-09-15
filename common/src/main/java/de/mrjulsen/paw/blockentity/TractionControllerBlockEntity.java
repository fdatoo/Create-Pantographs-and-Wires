package de.mrjulsen.paw.blockentity;

import java.util.List;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import de.mrjulsen.paw.traction.TrainSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds a train's settings and when they were chosen. On an assembled train the same data rides along as
 * the block's saved data, and the train's controllers all adopt the most recently chosen settings.
 */
public class TractionControllerBlockEntity extends SmartBlockEntity {
    private TrainSettings settings = TrainSettings.DEFAULT;
    private long changed;

    public TractionControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    public TrainSettings settings() {
        return settings;
    }

    /** @param changed when the settings were chosen, in milliseconds since the epoch */
    public void setSettings(TrainSettings settings, long changed) {
        this.settings = settings;
        this.changed = changed;
        notifyUpdate();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.put(TrainSettings.NBT_KEY, settings.write(changed));
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        settings = TrainSettings.from(tag);
        changed = TrainSettings.changedAt(tag);
    }
}
