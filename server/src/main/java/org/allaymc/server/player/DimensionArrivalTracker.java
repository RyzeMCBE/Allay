package org.allaymc.server.player;

import org.allaymc.api.utils.hash.HashUtils;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Connection-local, thread-safe observations for dimension travel. Tracks only
 * requests placed on the sending path, not network delivery or rendered terrain.
 * Never controls the client's loading screen or changes the dimension handshake.
 */
final class DimensionArrivalTracker {
    static final long PRIORITY_WINDOW_NANOS = 2_000_000_000L;
    static final long OBSERVATION_TIMEOUT_NANOS = 120_000_000_000L;

    private int sequence;
    private Active active;

    synchronized Integer begin(int dimensionId, double x, double z, int viewRadius,
                               boolean supportsScreenId, long now) {
        sequence = sequence == Integer.MAX_VALUE ? 1 : sequence + 1;
        var arrival = new HashSet<Long>();
        int centerX = Math.floorDiv((int) Math.floor(x), 16);
        int centerZ = Math.floorDiv((int) Math.floor(z), 16);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx * dx + dz * dz <= viewRadius * viewRadius) {
                    arrival.add(HashUtils.hashXZ(centerX + dx, centerZ + dz));
                }
            }
        }
        Integer screenId = supportsScreenId ? sequence : null;
        active = new Active(sequence, dimensionId, screenId, now, arrival);
        return screenId;
    }

    synchronized void serverAcknowledged(long now) {
        if (active != null && active.serverAckNanos == 0) {
            active.serverAckNanos = now;
        }
    }

    synchronized boolean isPrioritizing(long now) {
        return active != null
                && !active.pending.isEmpty()
                && active.serverAckNanos != 0
                && now - active.serverAckNanos < PRIORITY_WINDOW_NANOS;
    }

    synchronized boolean isArrivalChunk(long hash) {
        return active != null && active.arrival.contains(hash);
    }

    synchronized void chunkQueued(int dimensionId, int x, int z) {
        if (active != null && active.dimensionId == dimensionId) {
            active.pending.remove(HashUtils.hashXZ(x, z));
        }
    }

    /** Returns false for stale, untagged or otherwise unrelated screen notifications. */
    synchronized boolean screenSignal(boolean started, Integer screenId, long now) {
        if (active == null || !Objects.equals(active.screenId, screenId)) {
            return false;
        }
        if (started) {
            if (active.screenStartedNanos == 0) {
                active.screenStartedNanos = now;
            }
        } else if (active.screenEndedNanos == 0) {
            active.screenEndedNanos = now;
        }
        return true;
    }

    synchronized Summary completeIfReady(long now) {
        if (active == null || active.serverAckNanos == 0 || active.screenEndedNanos == 0) {
            return null;
        }
        return finish("screen_ended", now);
    }

    synchronized Summary expire(long now) {
        if (active == null || now - active.startedNanos < OBSERVATION_TIMEOUT_NANOS) {
            return null;
        }
        return finish("observation_expired", now);
    }

    synchronized void clear() {
        active = null;
    }

    private Summary finish(String result, long now) {
        var current = active;
        active = null;
        return new Summary(current.sequence, current.dimensionId, result,
                current.arrival.size(), current.arrival.size() - current.pending.size(),
                Math.max(0L, (now - current.startedNanos) / 1_000_000L),
                current.screenStartedNanos != 0, current.serverAckNanos != 0);
    }

    record Summary(int sequence, int dimensionId, String result, int expectedChunks,
                   int queuedChunks, long elapsedMillis, boolean screenStarted,
                   boolean serverAcknowledged) {
    }

    private static final class Active {
        final int sequence;
        final int dimensionId;
        final Integer screenId;
        final long startedNanos;
        final Set<Long> arrival;
        final Set<Long> pending;
        long serverAckNanos;
        long screenStartedNanos;
        long screenEndedNanos;

        Active(int sequence, int dimensionId, Integer screenId, long startedNanos,
               Set<Long> arrival) {
            this.sequence = sequence;
            this.dimensionId = dimensionId;
            this.screenId = screenId;
            this.startedNanos = startedNanos;
            this.arrival = Set.copyOf(arrival);
            this.pending = new HashSet<>(arrival);
        }
    }
}
