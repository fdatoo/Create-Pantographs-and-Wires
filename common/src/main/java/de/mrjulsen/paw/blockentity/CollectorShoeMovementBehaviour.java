package de.mrjulsen.paw.blockentity;

import org.joml.Vector3d;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import de.mrjulsen.paw.block.CollectorShoeBlock;
import de.mrjulsen.paw.client.sound.TractionSoundManager;
import de.mrjulsen.paw.traction.ElectricSupply;
import de.mrjulsen.paw.traction.ThirdRailContactDetector;
import de.mrjulsen.paw.traction.TractionSpeedSync;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Reports a collector shoe to the traction pipeline exactly as a pantograph reports: every client
 * tick, whether it is touching a third rail. The tracker combines it with any pantographs on the same
 * train, so a train drawing power from either keeps its traction sound.
 *
 * A moving shoe in contact also arcs: a steady trickle of sparks where it slides along the conductor,
 * and a bright burst whenever it makes or breaks contact, as at rail ends and gaps.
 */
public class CollectorShoeMovementBehaviour implements MovementBehaviour {
    /** Where the shoe meets the conductor, from the shoe block's centre, matching the collector_shoe model. */
    private static final double CONTACT_OUTWARD = 0.875;
    private static final double CONTACT_DOWN = 1.08;
    /** Below this speed (blocks per tick) a shoe neither trickles sparks nor bursts. */
    private static final double SPARKING_SPEED = 0.05;
    private static final double BURST_SPEED = 0.02;

    /** Per-shoe memory between ticks, for spotting when contact is made or broken. */
    private static final class ShoeState {
        private boolean touching;
    }

    @Override
    public void tick(MovementContext context) {
        AbstractContraptionEntity entity = context.contraption.entity;
        Level level = entity.level();
        if (!level.isClientSide()) {
            TractionSpeedSync.tick(entity);
            if (context.position != null && touches(level, context)) {
                ElectricSupply.recordContact(entity, "collector shoe");
            }
            return;
        }
        if (context.position == null) {
            return;
        }
        Direction facing = context.state.getValue(CollectorShoeBlock.FACING);
        Vec3 outward = context.rotation.apply(Vec3.atLowerCornerOf(facing.getNormal()));
        Vec3 up = context.rotation.apply(new Vec3(0, 1, 0));

        boolean touching = touches(level, context);
        double speed = context.motion.length();

        ShoeState state = context.temporaryData instanceof ShoeState existing ? existing : new ShoeState();
        context.temporaryData = state;
        Vec3 contact = context.position.add(outward.scale(CONTACT_OUTWARD)).add(up.scale(-CONTACT_DOWN));
        if (touching != state.touching && speed > BURST_SPEED) {
            burst(level, contact);
        } else if (touching && speed > SPARKING_SPEED) {
            trickle(level, contact, speed);
        }
        state.touching = touching;

        TractionSoundManager.observe(
            PantographMovementBehaviour.vehicleId(entity),
            level.getGameTime(),
            true,
            touching,
            speed,
            context.position.x,
            context.position.y,
            context.position.z,
            TractionSoundManager.listenerAboard(entity)
        );
    }

    /** Whether the shoe is on a third rail's conductor. Works on either side; rails are indexed on both. */
    private static boolean touches(Level level, MovementContext context) {
        Direction facing = context.state.getValue(CollectorShoeBlock.FACING);
        Vec3 outward = context.rotation.apply(Vec3.atLowerCornerOf(facing.getNormal()));
        Vec3 up = context.rotation.apply(new Vec3(0, 1, 0));
        Vec3 forward = context.rotation.apply(Vec3.atLowerCornerOf(facing.getClockWise().getNormal()));
        return ThirdRailContactDetector.touches(level, joml(context.position), joml(outward), joml(up), joml(forward));
    }

    /** Making or breaking contact under load: a flash of sparks flying off the shoe. */
    private static void burst(Level level, Vec3 at) {
        RandomSource random = level.random;
        for (int i = 0; i < 24; i++) {
            spark(level, ParticleTypes.ELECTRIC_SPARK, at, random, 1.2);
        }
        for (int i = 0; i < 6; i++) {
            spark(level, ParticleTypes.END_ROD, at, random, 0.12);
        }
    }

    /** Sliding contact: an occasional spark, more often the faster the train runs. */
    private static void trickle(Level level, Vec3 at, double speed) {
        RandomSource random = level.random;
        if (random.nextDouble() < Math.min(0.8, speed * 1.5)) {
            int count = 2 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                spark(level, ParticleTypes.ELECTRIC_SPARK, at, random, 0.8);
            }
        }
        if (random.nextDouble() < Math.min(0.25, speed * 0.4)) {
            spark(level, ParticleTypes.END_ROD, at, random, 0.06);
        }
    }

    private static void spark(Level level, ParticleOptions type, Vec3 at, RandomSource random, double velocity) {
        level.addParticle(type,
            at.x + (random.nextDouble() - 0.5) * 0.3,
            at.y + random.nextDouble() * 0.1,
            at.z + (random.nextDouble() - 0.5) * 0.3,
            (random.nextDouble() - 0.5) * velocity,
            random.nextDouble() * velocity * 0.6,
            (random.nextDouble() - 0.5) * velocity);
    }

    private static Vector3d joml(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
