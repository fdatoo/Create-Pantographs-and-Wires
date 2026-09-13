package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ElectricTrainStateTrackerTest {
    private static final UUID TRAIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TRAIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void oneContactAmongSeveralPantographsPowersTrain() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(0, 0);

        tracker.observe(TRAIN_ID, 100, true, false);
        tracker.observe(TRAIN_ID, 100, true, true);
        tracker.observe(TRAIN_ID, 100, false, false);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, 100);
        assertTrue(snapshot.capable());
        assertTrue(snapshot.powered());
        assertEquals(100, snapshot.lastCapabilityTick());
        assertEquals(100, snapshot.lastContactTick());
    }

    @Test
    void loweredPantographsRemainCapableButNotPowered() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(0, 0);

        tracker.observe(TRAIN_ID, 100, false, true);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, 100);
        assertTrue(snapshot.capable());
        assertFalse(snapshot.powered());
        assertEquals(100, snapshot.lastCapabilityTick());
        assertEquals(-1, snapshot.lastContactTick());
    }

    @Test
    void contactSurvivesConfiguredGraceWindow() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(3, 3);
        tracker.observe(TRAIN_ID, 100, true, true);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, 103);

        assertTrue(snapshot.powered());
    }

    @Test
    void contactExpiresAfterGraceWindow() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(3, 3);
        tracker.observe(TRAIN_ID, 100, true, true);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, 104);

        assertFalse(snapshot.powered());
        assertEquals(100, snapshot.lastContactTick());
    }

    @Test
    void capabilityExpiresLaterThanContact() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(1, 4);
        tracker.observe(TRAIN_ID, 100, true, true);

        ElectricTrainSnapshot afterContactExpiry = tracker.snapshot(TRAIN_ID, 102);
        ElectricTrainSnapshot afterCapabilityExpiry = tracker.snapshot(TRAIN_ID, 105);

        assertFalse(afterContactExpiry.powered());
        assertTrue(afterContactExpiry.capable());
        assertFalse(afterCapabilityExpiry.powered());
        assertFalse(afterCapabilityExpiry.capable());
    }

    @Test
    void contactAndCapabilityExpireIndependently() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(4, 1);
        tracker.observe(TRAIN_ID, 100, true, true);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, 102);

        assertTrue(snapshot.powered());
        assertFalse(snapshot.capable());
    }

    @Test
    void observationsFromNewTickResetThePerTickOrAccumulator() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(0, 0);
        tracker.observe(TRAIN_ID, 100, true, true);
        tracker.observe(TRAIN_ID, 100, false, false);
        assertTrue(tracker.snapshot(TRAIN_ID, 100).powered());

        tracker.observe(TRAIN_ID, 101, true, false);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, 101);
        assertTrue(snapshot.capable());
        assertFalse(snapshot.powered());
        assertEquals(100, snapshot.lastContactTick());
    }

    @Test
    void observationAtMinusOneIsTracked() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(1, 1);
        tracker.observe(TRAIN_ID, -1, true, true);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, 0);

        assertTrue(snapshot.capable());
        assertTrue(snapshot.powered());
        assertEquals(-1, snapshot.lastCapabilityTick());
        assertEquals(-1, snapshot.lastContactTick());
    }

    @Test
    void graceWindowDoesNotOverflowAcrossTheLongRange() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(Long.MAX_VALUE, Long.MAX_VALUE);
        tracker.observe(TRAIN_ID, Long.MIN_VALUE, true, true);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, Long.MAX_VALUE);

        assertFalse(snapshot.capable());
        assertFalse(snapshot.powered());
    }

    @Test
    void staleObservationDoesNotOverwriteNewerState() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(0, 0);
        tracker.observe(TRAIN_ID, 100, true, true);

        tracker.observe(TRAIN_ID, 99, false, false);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, 100);
        assertTrue(snapshot.capable());
        assertTrue(snapshot.powered());
        assertEquals(100, snapshot.lastCapabilityTick());
        assertEquals(100, snapshot.lastContactTick());
    }

    @Test
    void removeClearsTrainState() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(3, 40);
        tracker.observe(TRAIN_ID, 100, true, true);

        tracker.remove(TRAIN_ID);

        ElectricTrainSnapshot snapshot = tracker.snapshot(TRAIN_ID, 100);
        assertFalse(snapshot.capable());
        assertFalse(snapshot.powered());
        assertEquals(-1, snapshot.lastCapabilityTick());
        assertEquals(-1, snapshot.lastContactTick());
    }

    @Test
    void clearRemovesAllTrainState() {
        ElectricTrainStateTracker tracker = new ElectricTrainStateTracker(3, 40);
        tracker.observe(TRAIN_ID, 100, true, true);
        tracker.observe(OTHER_TRAIN_ID, 100, false, false);

        tracker.clear();

        assertFalse(tracker.snapshot(TRAIN_ID, 100).capable());
        assertFalse(tracker.snapshot(OTHER_TRAIN_ID, 100).capable());
    }

    @Test
    void constructorRejectsNegativeGraceValues() {
        assertThrows(IllegalArgumentException.class, () -> new ElectricTrainStateTracker(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ElectricTrainStateTracker(0, -1));
    }
}
