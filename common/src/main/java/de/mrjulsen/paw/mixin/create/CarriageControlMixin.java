package de.mrjulsen.paw.mixin.create;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.track.BezierConnection;

import de.mrjulsen.paw.traction.ManualThrottle;
import de.mrjulsen.paw.traction.TractionDebug;
import de.mrjulsen.paw.traction.TrackSlopes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * Hand driving on Create trains, for traction that behaves on hills.
 *
 * Create builds every slope as a curved track edge, and CarriageContraptionEntity.control caps a
 * hand-driven train to its turning speed whenever a carriage is on one, so every hill braked the train.
 * Scheduled trains already skip gentle straight slopes (Navigation); the redirect applies the same rule
 * here (TrackSlopes).
 *
 * The injection records whether the driver is holding a direction, so the traction sound can tell a
 * train held back by Create's remaining limits (curves, steep slopes) from one being braked.
 *
 * Both are require=0: if Create's internals move, hand driving goes back to stock rather than the game
 * refusing to start.
 */
@Mixin(CarriageContraptionEntity.class)
public abstract class CarriageControlMixin {

    @Redirect(
        method = "control",
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/graph/TrackEdge;isTurn()Z", remap = false),
        remap = false,
        require = 0
    )
    private boolean paw$gentleSlopesAreNotTurns(TrackEdge edge) {
        TractionDebug.once("slope-rule", "server: hand-driving slope rule is active (Create's manual control is hooked)");
        if (!edge.isTurn()) {
            return false;
        }
        BezierConnection turn = edge.getTurn();
        double rise = Math.abs(turn.starts.getFirst().y - turn.starts.getSecond().y);
        boolean gentle = TrackSlopes.isGentleStraightSlope(
            turn.starts.getFirst().y, turn.starts.getSecond().y,
            turn.axes.getFirst().x, turn.axes.getFirst().z,
            turn.axes.getSecond().x, turn.axes.getSecond().z,
            turn.getLength());
        if (TractionDebug.server()) {
            CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
            if (TractionDebug.newEdge(self.trainId, turn, self.level().getGameTime())) {
                String what = gentle ? "a gentle straight slope, so speed is kept"
                    : rise > 1 / 16.0 ? "a slope that is steep or curved, so speed is capped to the turning limit"
                    : "a curve, so speed is capped to the turning limit";
                double ax = turn.axes.getFirst().x;
                double az = turn.axes.getFirst().z;
                double bx = turn.axes.getSecond().x;
                double bz = turn.axes.getSecond().z;
                TractionDebug.info("server: hand-driven train {} is on {} (rise {} over {} blocks, {}%; {} in plan, end directions ({}, {}) and ({}, {}))",
                    TractionDebug.shortId(self.trainId), what, String.format("%.2f", rise), String.format("%.1f", turn.getLength()),
                    String.format("%.1f", rise / Math.max(1e-6, turn.getLength()) * 100),
                    TrackSlopes.isStraight(ax, az, bx, bz) ? "straight" : "curving",
                    String.format("%+.3f", ax), String.format("%+.3f", az), String.format("%+.3f", bx), String.format("%+.3f", bz));
            }
        }
        return !gentle;
    }

    @Inject(method = "control", at = @At("RETURN"), remap = false, require = 0)
    private void paw$recordThrottle(BlockPos controlsLocalPos, Collection<Integer> heldControls, Player player, CallbackInfoReturnable<Boolean> cir) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        Carriage carriage = self.getCarriage();
        if (carriage == null || carriage.train == null) {
            return;
        }
        Train train = carriage.train;
        boolean held = train.targetSpeed != 0 && Math.signum(train.targetSpeed) == Math.signum(train.speed);
        ManualThrottle.record(train.id, self.level().getGameTime(), held);
    }
}
