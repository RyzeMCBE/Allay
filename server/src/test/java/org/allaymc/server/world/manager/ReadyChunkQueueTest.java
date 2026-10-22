package org.allaymc.server.world.manager;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import org.allaymc.api.world.chunk.Chunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReadyChunkQueueTest {

    @Test
    void readyChunksAreNotBlockedByOneMissingChunkAndBudgetIsRespected() {
        var queue = new LongArrayFIFOQueue();
        for (long id : new long[]{1, 2, 3, 4}) {
            queue.enqueue(id);
        }
        Chunk two = mock(Chunk.class);
        Chunk three = mock(Chunk.class);
        var selected = ReadyChunkQueue.takeReady(
                queue, 2,
                id -> id == 2 ? two : id == 3 ? three : null,
                id -> true
        );

        assertEquals(2, selected.size());
        assertArrayEquals(new long[]{2, 3}, selected.keySet().toLongArray());
        assertEquals(2, queue.size());
        assertEquals(1L, queue.dequeueLong());
        assertEquals(4L, queue.dequeueLong());
    }

    @Test
    void deferredArrivalChunksRemainAheadOfDistantChunks() {
        var queue = new LongArrayFIFOQueue();
        for (long id : new long[]{1, 2, 3, 4}) {
            queue.enqueue(id);
        }
        Chunk nearby = mock(Chunk.class);
        var selected = ReadyChunkQueue.takeReady(queue, 5,
                id -> id == 2 || id == 4 ? nearby : null,
                id -> id <= 3
        );

        assertArrayEquals(new long[]{2}, selected.keySet().toLongArray());
        assertEquals(3, queue.size());
        assertEquals(1L, queue.dequeueLong());
        assertEquals(3L, queue.dequeueLong());
        assertEquals(4L, queue.dequeueLong());
    }

    @Test
    void emptyQueueAndZeroBudgetDoNotRequestChunks() {
        var queue = new LongArrayFIFOQueue();
        queue.enqueue(7);
        assertTrue(ReadyChunkQueue.takeReady(queue, 0,
                id -> { throw new AssertionError("Must not read chunks"); }, id -> true).isEmpty());
        assertEquals(7L, queue.dequeueLong());
    }

    @Test
    void doesNotScanAnUnboundedViewDistanceQueue() {
        var queue = new LongArrayFIFOQueue();
        for (long hash = 1; hash <= 100; hash++) {
            queue.enqueue(hash);
        }
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var selected = ReadyChunkQueue.takeReady(queue, 1,
                id -> { attempts.incrementAndGet(); return null; }, id -> true);

        assertTrue(selected.isEmpty());
        assertEquals(9, attempts.get(), "The one-chunk budget should scan at most nine entries");
        assertEquals(100, queue.size());
        assertEquals(1L, queue.dequeueLong(), "Unavailable nearby chunk retains priority");
    }
}
