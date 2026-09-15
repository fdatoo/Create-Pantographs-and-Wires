package de.mrjulsen.paw.traction;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

/**
 * Server side: which trains are drawing power, from a raised pantograph touching a wire or a collector
 * shoe on a third rail. Create only gives a train its powered top speed and acceleration while it has
 * fuel burning, so a train with supply is kept fuelled (TrainFuelMixin) without burning anything.
 *
 * Supply outlasts contact by GRACE_TICKS, so the insulator gaps and rail ends a train rolls across don't
 * drop it to unpowered for a moment. Used on the server thread only.
 */
public final class ElectricSupply {
    /** The server's record, advanced once per server tick. */
    public static final ElectricSupply SERVER = new ElectricSupply();

    /** How long supply lasts after the last contact, matching the traction sound's gap tolerance. */
    public static final long GRACE_TICKS = 30;
    /** Fuel a supplied train is kept at: after supply ends, this many ticks of driving remain powered. */
    public static final int FUEL_TICKS = 40;
    private static final long FORGET_AFTER_TICKS = 1200;

    private final Map<UUID, Long> lastContact = new HashMap<>();
    private long now;

    /** Call once per server tick. */
    public void tick() {
        now++;
        lastContact.values().removeIf(tick -> now - tick > FORGET_AFTER_TICKS);
    }

    /** @return whether the train had no supply until now */
    public boolean record(UUID trainId) {
        Long previous = lastContact.put(trainId, now);
        return previous == null || now - previous > GRACE_TICKS;
    }

    public boolean supplied(UUID trainId) {
        Long last = lastContact.get(trainId);
        return last != null && now - last <= GRACE_TICKS;
    }

    public void clear() {
        lastContact.clear();
        now = 0;
    }

    /** Records contact for the train a carriage belongs to; contraptions that aren't trains are ignored. */
    public static void recordContact(AbstractContraptionEntity entity, String collector) {
        if (!(entity instanceof CarriageContraptionEntity carriage) || carriage.trainId == null) {
            return;
        }
        if (SERVER.record(carriage.trainId) && TractionDebug.server()) {
            TractionDebug.info("server: train {} is drawing power ({}), so Create treats it as fuelled",
                TractionDebug.shortId(carriage.trainId), collector);
        }
    }
}
