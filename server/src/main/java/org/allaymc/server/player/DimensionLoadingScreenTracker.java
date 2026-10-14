package org.allaymc.server.player;

import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;

import java.util.Objects;

/**
 * Tracks one Bedrock dimension loading screen per player.
 *
 * <p>Bedrock protocol >= 712 supports a unique optional loadingScreenId on
 * ChangeDimensionPacket. The same ID is echoed in ServerboundLoadingScreenPacket.
 * Chunks must keep flowing while the client shows the screen: END_LOADING_SCREEN
 * is an observation from the client, not a prerequisite for sending chunks.
 *
 * <p>All methods are synchronized because network receive and chunk encoding
 * execute on different threads.
 */
final class DimensionLoadingScreenTracker {
    private int nextId;
    private Integer activeId;
    private long startedAtNanos;
    private long firstChunkAtNanos;
    private long clientAcknowledgedAtNanos;
    private int chunksQueued;
    private boolean clientStarted;
    private boolean clientFinished;

    synchronized int begin(long timestampNanos) {
        nextId = nextId == Integer.MAX_VALUE ? 1 : nextId + 1;
        activeId = nextId;
        startedAtNanos = timestampNanos;
        firstChunkAtNanos = 0;
        clientAcknowledgedAtNanos = 0;
        chunksQueued = 0;
        clientStarted = false;
        clientFinished = false;
        return nextId;
    }

    synchronized void chunkQueued(long timestampNanos) {
        if (activeId == null || clientFinished) {
            return;
        }
        if (firstChunkAtNanos == 0) {
            firstChunkAtNanos = timestampNanos;
        }
        chunksQueued++;
    }

    synchronized Observation clientAcknowledged(long timestampNanos) {
        if (activeId == null) {
            return new Observation(false, null, 0, -1, 0, false, false);
        }
        clientAcknowledgedAtNanos = timestampNanos;
        return observation(true, timestampNanos);
    }

    synchronized Observation clientScreenEvent(ServerboundLoadingScreenPacketType type,
                                               Integer receivedId, long timestampNanos) {
        // Never let a late packet from another transition complete the current one.
        if (activeId == null || !Objects.equals(receivedId, activeId)) {
            return observation(false, timestampNanos);
        }
        if (type == ServerboundLoadingScreenPacketType.START_LOADING_SCREEN) {
            clientStarted = true;
        } else if (type == ServerboundLoadingScreenPacketType.END_LOADING_SCREEN) {
            clientFinished = true;
        }
        return observation(true, timestampNanos);
    }

    synchronized Integer activeId() {
        return activeId;
    }

    synchronized Observation snapshot(long timestampNanos) {
        return observation(activeId != null, timestampNanos);
    }

    private Observation observation(boolean matched, long timestampNanos) {
        if (activeId == null) {
            return new Observation(false, null, 0, -1, 0, false, false);
        }
        return new Observation(matched, activeId,
                (timestampNanos - startedAtNanos) / 1_000_000,
                firstChunkAtNanos == 0 ? -1 : (firstChunkAtNanos - startedAtNanos) / 1_000_000,
                chunksQueued, clientStarted, clientFinished);
    }

    record Observation(boolean matched, Integer loadingScreenId, long elapsedMillis,
                       long firstChunkMillis, int chunksQueued,
                       boolean clientStarted, boolean clientFinished) {}
}
