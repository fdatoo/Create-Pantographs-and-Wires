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

/**
 * Server side: sends the players near a carriage carrying a current collector its train's exact speed,
 * every tick while it moves and now and then at rest. Called by collector movement behaviours; a
 * carriage with several collectors still sends once per tick.
 */
public final class TractionSpeedSync {
    private static final long HEARTBEAT_TICKS = 20;

    private static final class Sent {
        private long tick = Long.MIN_VALUE;
        private long sentTick = Long.MIN_VALUE;
        private double speed = -1;
    }

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
        double speed = Math.abs(carriage.train.speed);
        if (speed == 0 && sent.speed == 0 && tick - sent.sentTick < HEARTBEAT_TICKS) {
            return;
        }
        sent.sentTick = tick;
        sent.speed = speed;
        level.getChunkSource().broadcast(entity, PantographsAndWires.net().CHANNEL.toPacket(NetworkManager.Side.S2C,
            new TractionSpeedPacket(carriageEntity.trainId, tick, (float) speed)));
    }
}
