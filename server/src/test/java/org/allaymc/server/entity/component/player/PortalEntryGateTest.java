package org.allaymc.server.entity.component.player;

import org.allaymc.api.player.ClientState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalEntryGateTest {
    @Test
    void portalNeverStartsDuringLoginOrAnotherDimensionTransition() {
        for (var state : new ClientState[]{
                ClientState.CONNECTED, ClientState.LOGGED_IN, ClientState.SPAWNED, ClientState.DISCONNECTED}) {
            assertFalse(PortalEntryGate.mayTickPortal(true, state, false), state.name());
        }
        assertFalse(PortalEntryGate.mayTickPortal(true, ClientState.IN_GAME, true));
        assertFalse(PortalEntryGate.mayTickPortal(true, null, false));
    }

    @Test
    void fullyJoinedCreativePlayersRemainEligibleImmediately() {
        // The gate adds no dwell time. The existing creative/spectator branch
        // in tickPortal still triggers on the first eligible collision tick.
        assertTrue(PortalEntryGate.mayTickPortal(true, ClientState.IN_GAME, false));
        assertTrue(PortalEntryGate.mayTickPortal(false, null, false));
    }
}
