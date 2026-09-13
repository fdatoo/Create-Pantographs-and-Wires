package de.mrjulsen.paw.blockentity;

import org.joml.Vector3d;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import de.mrjulsen.paw.block.CollectorShoeBlock;
import de.mrjulsen.paw.client.sound.TractionSoundManager;
import de.mrjulsen.paw.traction.ThirdRailContactDetector;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Reports a collector shoe to the traction pipeline exactly as a pantograph reports: every client
 * tick, whether it is touching a third rail. The tracker combines it with any pantographs on the same
 * train, so a train drawing power from either keeps its traction sound.
 */
public class CollectorShoeMovementBehaviour implements MovementBehaviour {

    @Override
    public void tick(MovementContext context) {
        AbstractContraptionEntity entity = context.contraption.entity;
        if (!entity.level().isClientSide() || context.position == null) {
            return;
        }
        Direction facing = context.state.getValue(CollectorShoeBlock.FACING);
        Vec3 outward = context.rotation.apply(Vec3.atLowerCornerOf(facing.getNormal()));
        Vec3 up = context.rotation.apply(new Vec3(0, 1, 0));
        Vec3 forward = context.rotation.apply(Vec3.atLowerCornerOf(facing.getClockWise().getNormal()));

        boolean touching = ThirdRailContactDetector.touches(entity.level(), joml(context.position), joml(outward), joml(up), joml(forward));

        TractionSoundManager.observe(
            PantographMovementBehaviour.vehicleId(entity),
            entity.level().getGameTime(),
            true,
            touching,
            context.motion.length(),
            context.position.x,
            context.position.y,
            context.position.z
        );
    }

    private static Vector3d joml(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
