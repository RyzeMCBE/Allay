package org.allaymc.server.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionArrivalTrackerTest {
    private static final long START = 1_000_000_000L;

    @Test
    void correlatesScreenIdAndObservesNineQueuedChunks() {
        var tracker = new DimensionArrivalTracker();
        Integer id = tracker.begin(1, 0, 0, 8, true, START);
        assertNotNull(id);
        assertFalse(tracker.isPrioritizing(START + 10));
        tracker.serverAcknowledged(START + 10);
        assertTrue(tracker.isPrioritizing(START + 11));

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                tracker.chunkQueued(1, x, z);
            }
        }
        assertFalse(tracker.isPrioritizing(START + 12));
        assertTrue(tracker.screenSignal(true, id, START + 100));
        assertTrue(tracker.screenSignal(false, id, START + 1_000_000_000L));

        var summary = tracker.completeIfReady(START + 1_000_000_000L);
        assertNotNull(summary);
        assertEquals(9, summary.expectedChunks());
        assertEquals(9, summary.queuedChunks());
        assertEquals("screen_ended", summary.result());
        assertTrue(summary.screenStarted());
        assertTrue(summary.serverAcknowledged());
        assertNull(tracker.completeIfReady(START + 1_000_000_001L));
    }

    @Test
    void staleOrUntaggedScreenCannotFinishNewTransition() {
        var tracker = new DimensionArrivalTracker();
        Integer oldId = tracker.begin(1, 0, 0, 8, true, START);
        Integer newId = tracker.begin(0, 80, -48, 8, true, START + 1_000);
        assertNotEquals(oldId, newId);
        tracker.serverAcknowledged(START + 2_000);
        assertFalse(tracker.screenSignal(false, oldId, START + 3_000));
        assertFalse(tracker.screenSignal(false, null, START + 3_000));
        assertNull(tracker.completeIfReady(START + 4_000));
        assertTrue(tracker.screenSignal(false, newId, START + 5_000));
        assertNotNull(tracker.completeIfReady(START + 6_000));
    }

    @Test
    void prioritizationExpiresWithoutBlockingNormalChunkSending() {
        var tracker = new DimensionArrivalTracker();
        tracker.begin(1, 0, 0, 8, false, START);
        tracker.serverAcknowledged(START + 10);
        assertTrue(tracker.isPrioritizing(START + 20));
        assertFalse(tracker.isPrioritizing(START + 10 + DimensionArrivalTracker.PRIORITY_WINDOW_NANOS));
        assertTrue(tracker.screenSignal(false, null, START + 3_000_000_000L));
        assertNotNull(tracker.completeIfReady(START + 3_000_000_001L));
    }

    @Test
    void timeoutOnlyExpiresObservationAndDoesNotForceScreenCompletion() {
        var tracker = new DimensionArrivalTracker();
        tracker.begin(1, 0, 0, 8, true, START);
        assertNull(tracker.expire(START + DimensionArrivalTracker.OBSERVATION_TIMEOUT_NANOS - 1));
        var summary = tracker.expire(START + DimensionArrivalTracker.OBSERVATION_TIMEOUT_NANOS);
        assertNotNull(summary);
        assertEquals("observation_expired", summary.result());
        assertNull(tracker.expire(START + DimensionArrivalTracker.OBSERVATION_TIMEOUT_NANOS + 1));
    }

    @Test
    void reducedChunkRadiusUsesOnlyChunksActuallyRequested() {
        var tracker = new DimensionArrivalTracker();
        tracker.begin(2, -32, -32, 1, false, START);
        tracker.serverAcknowledged(START + 1);
        tracker.chunkQueued(2, -2, -2);
        tracker.chunkQueued(2, -3, -2);
        tracker.chunkQueued(2, -1, -2);
        tracker.chunkQueued(2, -2, -3);
        tracker.chunkQueued(2, -2, -1);
        assertFalse(tracker.isPrioritizing(START + 2));
        tracker.screenSignal(false, null, START + 3);
        var summary = tracker.completeIfReady(START + 4);
        assertNotNull(summary);
        assertEquals(5, summary.expectedChunks());
        assertEquals(5, summary.queuedChunks());
    }
}
