package de.mrjulsen.paw.blockentity;

import java.util.UUID;
import java.util.function.UnaryOperator;

import org.joml.Vector3d;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.foundation.utility.VecHelper;

import de.mrjulsen.paw.client.sound.TractionSoundManager;
import de.mrjulsen.paw.traction.ElectricSupply;
import de.mrjulsen.paw.traction.TractionSpeedSync;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;

public class PantographMovementBehaviour implements MovementBehaviour {

	@Override
	public void tick(MovementContext context) {
        if (!context.contraption.entity.level().isClientSide()) {
            TractionSpeedSync.tick(context.contraption.entity);
            // Raising or lowering a pantograph on a moving train updates this data on both sides.
            if (context.position != null && context.blockEntityData != null
                && context.blockEntityData.getBoolean(PantographBlockEntity.NBT_EXPANDABLE)
                && PantographBlockEntity.ContactProbe.of(contactBase(context), contactRotation(context))
                    .find(context.contraption.entity.level()).touching()) {
                ElectricSupply.recordContact(context.contraption.entity, "pantograph");
            }
            return;
        }
        if (context.contraption.entity.level().isClientSide() &&
            context.contraption.presentBlockEntities.containsKey(context.localPos) &&
            context.contraption.presentBlockEntities.get(context.localPos) instanceof PantographBlockEntity be
        ) {
            be.updateContraptionValues(contactBase(context), contactRotation(context));
            be.contraptionTick();

            long gameTime = context.contraption.entity.level().getGameTime();
            TractionSoundManager.observe(
                vehicleId(context.contraption.entity),
                gameTime,
                be.isExpandable(),
                be.isTouchingWire(),
                context.motion.length(),
                context.position.x(),
                context.position.y(),
                context.position.z(),
                TractionSoundManager.listenerAboard(context.contraption.entity)
            );        }
	}

    /** The pantograph's base in the world, where its collector geometry starts. */
    private static Vector3d contactBase(MovementContext context) {
        return new Vector3d(context.position.x(), context.position.y() - 0.5D + PantographBlockEntity.MIN_HEIGHT, context.position.z());
    }

    /** Turns the pantograph's own axes into world axes, following the train. */
    private static UnaryOperator<Vector3d> contactRotation(MovementContext context) {
        Direction dir = context.state.getValue(HorizontalDirectionalBlock.FACING);
        if (dir.getAxis() == Axis.X) {
            dir = dir.getOpposite();
        }
        final double yRot = dir.toYRot();
        return (v) -> {
            Vec3 r = VecHelper.rotate(new Vec3(v.x(), v.y(), v.z()), yRot, Axis.Y);
            r = context.rotation.apply(r);
            return new Vector3d(r.x(), r.y(), r.z());
        };
    }

    /**
     * A whole train (all its carriages/pantographs) shares one identity so the hum
     * doesn't stutter when only one of several pantographs loses contact; a
     * contraption outside Create's train system falls back to its own entity id.
     */
    public static UUID vehicleId(AbstractContraptionEntity entity) {
        if (entity instanceof CarriageContraptionEntity carriageEntity && carriageEntity.trainId != null) {
            return carriageEntity.trainId;
        }
        return entity.getUUID();
    }

    @Override
    public boolean renderAsNormalBlockEntity() {
        return true;
    }
}
