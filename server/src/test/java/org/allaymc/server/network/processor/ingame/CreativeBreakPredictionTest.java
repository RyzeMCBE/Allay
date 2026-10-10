package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.player.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CreativeBreakPredictionTest {
    @Test void creativeMissingOrMismatchedStartCanBeInitialized() {
        assertTrue(PlayerAuthInputPacketProcessor.needsCreativeStart(GameMode.CREATIVE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 64, -3));
        assertTrue(PlayerAuthInputPacketProcessor.needsCreativeStart(GameMode.CREATIVE,
                2, 64, -3, 1, 64, -3));
        assertFalse(PlayerAuthInputPacketProcessor.needsCreativeStart(GameMode.CREATIVE,
                1, 64, -3, 1, 64, -3));
    }
    @Test void survivalAdventureSpectatorNeverBypassProgress() {
        for (var mode : new GameMode[]{GameMode.SURVIVAL, GameMode.ADVENTURE, GameMode.SPECTATOR})
            assertFalse(PlayerAuthInputPacketProcessor.needsCreativeStart(mode,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 64, -3));
    }
}
