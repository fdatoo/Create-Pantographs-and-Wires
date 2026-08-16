package de.mrjulsen.paw.traction;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ElectricTrainStateTracker {
    private static final long NEVER = -1;

    private final long contactGraceTicks;
    private final long capabilityGraceTicks;
    private final Map<UUID, TrainState> states = new HashMap<>();

    public ElectricTrainStateTracker(long contactGraceTicks, long capabilityGraceTicks) {
        if (contactGraceTicks < 0) {
            throw new IllegalArgumentException("Contact grace ticks cannot be negative.");
        }
        if (capabilityGraceTicks < 0) {
            throw new IllegalArgumentException("Capability grace ticks cannot be negative.");
        }
        this.contactGraceTicks = contactGraceTicks;
        this.capabilityGraceTicks = capabilityGraceTicks;
    }

    public void observe(UUID trainId, long tick, boolean raised, boolean touching) {
        Objects.requireNonNull(trainId, "trainId");
        TrainState state = states.computeIfAbsent(trainId, ignored -> new TrainState());
        if (state.hasObservation && tick < state.observationTick) {
            return;
        }
        if (!state.hasObservation || state.observationTick != tick) {
            state.observationTick = tick;
            state.poweredThisTick = false;
        }

        state.hasObservation = true;
        state.lastCapabilityTick = tick;
        if (raised && touching) {
            state.poweredThisTick = true;
            state.hasContact = true;
            state.lastContactTick = tick;
        }
    }

    public ElectricTrainSnapshot snapshot(UUID trainId, long tick) {
        Objects.requireNonNull(trainId, "trainId");
        TrainState state = states.get(trainId);
        if (state == null) {
            return new ElectricTrainSnapshot(false, false, NEVER, NEVER);
        }

        boolean capable = isWithinGrace(state.hasObservation, state.lastCapabilityTick, tick, capabilityGraceTicks);
        boolean powered = state.hasObservation && state.observationTick == tick && state.poweredThisTick
            || isWithinGrace(state.hasContact, state.lastContactTick, tick, contactGraceTicks);
        return new ElectricTrainSnapshot(capable, powered, state.lastCapabilityTick, state.hasContact ? state.lastContactTick : NEVER);
    }

    public void remove(UUID trainId) {
        states.remove(Objects.requireNonNull(trainId, "trainId"));
    }

    public void clear() {
        states.clear();
    }

    private static boolean isWithinGrace(boolean observed, long observedTick, long tick, long graceTicks) {
        if (!observed || tick < observedTick) {
            return false;
        }
        long maximumTick = observedTick > Long.MAX_VALUE - graceTicks
            ? Long.MAX_VALUE
            : observedTick + graceTicks;
        return tick <= maximumTick;
    }

    private static final class TrainState {
        private boolean hasObservation;
        private long observationTick;
        private boolean poweredThisTick;
        private long lastCapabilityTick;
        private boolean hasContact;
        private long lastContactTick;
    }
}
