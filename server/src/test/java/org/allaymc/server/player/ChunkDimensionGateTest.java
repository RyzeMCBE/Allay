package org.allaymc.server.player;

import org.allaymc.api.world.dimension.DimensionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChunkDimensionGateTest {
    private final DimensionType overworld = mock(DimensionType.class);
    private final DimensionType nether = mock(DimensionType.class);

    @Test
    void suppressesQueuedOverworldChunksOnceNetherWasAnnounced() {
        assertTrue(ChunkDimensionGate.accepts(overworld, overworld, false, null));
        assertFalse(ChunkDimensionGate.accepts(nether, overworld, false, null));

        assertFalse(ChunkDimensionGate.accepts(overworld, overworld, true, nether));
        assertTrue(ChunkDimensionGate.accepts(nether, overworld, true, nether));
        assertFalse(ChunkDimensionGate.accepts(overworld, overworld, true, null));
    }

    @Test
    void suppressesOldChunksAfterDestinationCompletes() {
        assertFalse(ChunkDimensionGate.accepts(overworld, nether, false, null));
        assertTrue(ChunkDimensionGate.accepts(nether, nether, false, null));
    }
}
