package org.allaymc.server.player;

import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionLoadingScreenTrackerTest {
    @Test
    void eachDimensionTransferHasUniqueNonNullId() {
        var tracker = new DimensionLoadingScreenTracker();
        int first = tracker.begin(1_000_000_000L);
        int second = tracker.begin(2_000_000_000L);
        assertTrue(first > 0);
        assertEquals(first + 1, second);
        assertEquals(second, tracker.activeId());
    }

    @Test
    void closingScreenMeasuresReadinessButDoesNotGateChunkSending() {
        var tracker = new DimensionLoadingScreenTracker();
        int id = tracker.begin(1_000_000_000L);
        var start = tracker.clientScreenEvent(
                ServerboundLoadingScreenPacketType.START_LOADING_SCREEN, id, 1_050_000_000L
        );
        assertTrue(start.matched());
        assertTrue(start.clientStarted());

        // Send chunks immediately; client END is not needed to release them.
        tracker.chunkQueued(1_075_000_000L);
        tracker.chunkQueued(1_080_000_000L);
        var clientAck = tracker.clientAcknowledged(1_090_000_000L);
        assertEquals(2, clientAck.chunksQueued());
        assertEquals(75, clientAck.firstChunkMillis());
        assertEquals(90, clientAck.elapsedMillis());

        var end = tracker.clientScreenEvent(
                ServerboundLoadingScreenPacketType.END_LOADING_SCREEN, id, 4_000_000_000L
        );
        assertTrue(end.clientFinished());
        assertEquals(3_000, end.elapsedMillis());
    }

    @Test
    void previousScreenEndCannotFinishCurrentTransfer() {
        var tracker = new DimensionLoadingScreenTracker();
        int first = tracker.begin(1_000_000_000L);
        int current = tracker.begin(2_000_000_000L);

        var stale = tracker.clientScreenEvent(
                ServerboundLoadingScreenPacketType.END_LOADING_SCREEN, first, 2_100_000_000L
        );
        assertFalse(stale.matched());
        assertFalse(stale.clientFinished());

        var missingId = tracker.clientScreenEvent(
                ServerboundLoadingScreenPacketType.END_LOADING_SCREEN, null, 2_200_000_000L
        );
        assertFalse(missingId.matched());
        assertFalse(missingId.clientFinished());

        var finish = tracker.clientScreenEvent(
                ServerboundLoadingScreenPacketType.END_LOADING_SCREEN, current, 2_300_000_000L
        );
        assertTrue(finish.matched());
        assertTrue(finish.clientFinished());
    }

    @Test
    void nextTransferResetsTelemetryAndAllowsNewChunkPublication() {
        var tracker = new DimensionLoadingScreenTracker();
        int oldId = tracker.begin(100_000_000L);
        tracker.chunkQueued(120_000_000L);
        tracker.clientScreenEvent(ServerboundLoadingScreenPacketType.END_LOADING_SCREEN, oldId, 150_000_000L);

        int newId = tracker.begin(200_000_000L);
        assertNotEquals(oldId, newId);
        var current = tracker.snapshot(210_000_000L);
        assertFalse(current.clientFinished());
        assertEquals(-1, current.firstChunkMillis());
        assertEquals(0, current.chunksQueued());

        tracker.chunkQueued(230_000_000L);
        assertEquals(30, tracker.snapshot(250_000_000L).firstChunkMillis());
    }
}
