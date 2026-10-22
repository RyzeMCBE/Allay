package org.allaymc.server.world.manager;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.allaymc.api.world.chunk.Chunk;

import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/**
 * Bounded selection of already-loaded chunks. An unavailable center chunk must not
 * stall every other ready arrival chunk, but pending chunks retain their priority
 * at the front of the queue on the next tick. Nothing outside the existing queue
 * can be sent and no extra requests are created.
 */
final class ReadyChunkQueue {
    private ReadyChunkQueue() {
    }

    static Long2ObjectLinkedOpenHashMap<Chunk> takeReady(
            LongArrayFIFOQueue queue,
            int maxReady,
            LongFunction<Chunk> getChunk,
            LongPredicate isEligible
    ) {
        var result = new Long2ObjectLinkedOpenHashMap<Chunk>();
        if (maxReady <= 0 || queue.isEmpty()) {
            return result;
        }

        // Bound work per player/tick even if a large view-distance queue is unavailable.
        // The snapshot also prevents re-scanning deferred chunks in this tick.
        int maxToScan = (int) Math.min(queue.size(), Math.max(9L, 4L * maxReady));
        var deferred = new LongArrayList();

        for (int index = 0; index < maxToScan && result.size() < maxReady; index++) {
            long hash = queue.dequeueLong();
            if (!isEligible.test(hash)) {
                deferred.add(hash);
                continue;
            }
            Chunk chunk = getChunk.apply(hash);
            if (chunk == null) {
                deferred.add(hash);
            } else {
                result.put(hash, chunk);
            }
        }

        // Restore the original relative order of unavailable/unselected chunks.
        // They precede chunks which were not scanned due to the per-tick budget.
        for (int i = deferred.size() - 1; i >= 0; i--) {
            queue.enqueueFirst(deferred.getLong(i));
        }
        return result;
    }
}
