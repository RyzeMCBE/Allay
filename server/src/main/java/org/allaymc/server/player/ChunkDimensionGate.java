package org.allaymc.server.player;

import org.allaymc.api.world.dimension.DimensionType;

/**
 * Stateless guard for chunks produced by async senders while switching
 * dimensions. A queued chunk from the old dimension must never be sent after
 * ChangeDimensionPacket, or after the destination is installed.
 */
final class ChunkDimensionGate {
    private ChunkDimensionGate() {}

    static boolean accepts(DimensionType chunkDimension, DimensionType activeDimension,
                           boolean changingDimension, DimensionType destinationDimension) {
        var expected = changingDimension ? destinationDimension : activeDimension;
        return expected != null && expected == chunkDimension;
    }
}
