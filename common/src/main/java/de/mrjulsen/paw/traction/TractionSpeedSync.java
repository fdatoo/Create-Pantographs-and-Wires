package de.mrjulsen.paw.traction;

import java.util.Map;
import java.util.WeakHashMap;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.network.TractionSpeedPacket;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Server side: sends the players near a carriage carrying a current collector its train's exact speed
 * and the grade the carriage is on, every tick while it moves and now and then at rest. Called by
 * collector movement behaviours; a carriage with several collectors still sends once per tick.
 *
 * Create doesn't slow trains on hills, so the grade is what lets the sound tell a climb at steady
 * speed from cruising on the flat. It is measured from the carriage's own movement (rise over
 * horizontal distance, smoothed over a few ticks), which also gives it the right sign whichever way the
 * train runs.
 */
public final class TractionSpeedSync {
    private static final long HEARTBEAT_TICKS = 20;
    /** Share of the previous ticks' rise and run kept each tick: a time constant of about 5 ticks. */
    private static final double GRADE_MEMORY = 0.8;
    /** Smoothed horizontal distance below which the grade is left as it was, since a crawl measures nothing. */
    private static final double MIN_RUN = 0.05;
    private static final double MAX_GRADE = 4;

    private static final class Sent {
        private long tick = Long.MIN_VALUE;
        private long sentTick = Long.MIN_VALUE;
        private double speed = -1;
        private Vec3 lastPosition;
        private double rise;
        private double run;
        private double grade;
        private int reports;
        private long summaryTick = Long.MIN_VALUE;
    }

    private static final long SUMMARY_TICKS = 100;

    private static final Map<AbstractContraptionEntity, Sent> SENT = new WeakHashMap<>();

    private TractionSpeedSync() {}

    public static void tick(AbstractContraptionEntity entity) {
        if (!(entity instanceof CarriageContraptionEntity carriageEntity) || carriageEntity.trainId == null
            || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        Carriage carriage = carriageEntity.getCarriage();
        if (carriage == null || carriage.train == null) {
            return;
        }
        long tick = level.getGameTime();
        Sent sent = SENT.computeIfAbsent(entity, e -> new Sent());
        if (sent.tick == tick) {
            return;
        }
        sent.tick = tick;
        measureGrade(sent, entity.position());

        double speed = Math.abs(carriage.train.speed);
        boolean held = ManualThrottle.held(carriageEntity.trainId, tick);
        if (TractionDebug.server() && (sent.summaryTick == Long.MIN_VALUE || tick - sent.summaryTick >= SUMMARY_TICKS || tick < sent.summaryTick)) {
            if (sent.summaryTick != Long.MIN_VALUE) {
                TractionDebug.info("server: train {} carriage {}: {} m/s (target {} m/s), grade {}%, driver holding {}, {} speed reports sent in the last {} s",
                    TractionDebug.shortId(carriageEntity.trainId), carriageEntity.carriageIndex,
                    String.format("%.2f", speed * 20), String.format("%.2f", Math.abs(carriage.train.targetSpeed) * 20),
                    String.format("%+.1f", sent.grade * 100), held ? "yes" : "no", sent.reports, SUMMARY_TICKS / 20);
            }
            sent.summaryTick = tick;
            sent.reports = 0;
        }
        if (speed == 0 && sent.speed == 0 && tick - sent.sentTick < HEARTBEAT_TICKS) {
            return;
        }
        sent.sentTick = tick;
        sent.speed = speed;
        sent.reports++;
        TractionDebug.once("speed-sync", "server: sending train speed reports to nearby players");
        level.getChunkSource().broadcast(entity, PantographsAndWires.net().CHANNEL.toPacket(NetworkManager.Side.S2C,
            new TractionSpeedPacket(carriageEntity.trainId, tick, (float) speed, (float) sent.grade, held)));
    }

    private static void measureGrade(Sent sent, Vec3 position) {
        if (sent.lastPosition != null) {
            double dx = position.x - sent.lastPosition.x;
            double dz = position.z - sent.lastPosition.z;
            sent.rise = sent.rise * GRADE_MEMORY + (position.y - sent.lastPosition.y);
            sent.run = sent.run * GRADE_MEMORY + Math.sqrt(dx * dx + dz * dz);
            if (sent.run > MIN_RUN) {
                sent.grade = Math.max(-MAX_GRADE, Math.min(MAX_GRADE, sent.rise / sent.run));
            }
        }
        sent.lastPosition = position;
    }
}
