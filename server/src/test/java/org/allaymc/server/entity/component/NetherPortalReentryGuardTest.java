package org.allaymc.server.entity.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetherPortalReentryGuardTest {
    @Test
    void cannotReturnWhileTeleportIsPendingEvenAfterCooldownExpires() {
        var guard = new NetherPortalReentryGuard();
        guard.beginTransfer();

        for (int tick = 0; tick < 400; tick++) {
            assertTrue(guard.blocksEntry(true), "Portal must stay blocked during asynchronous loading");
        }

        guard.finishTransfer(true);
        for (int tick = 0; tick < 400; tick++) {
            assertTrue(guard.blocksEntry(true), "Still standing in the Nether portal must not reverse transfer");
        }
    }

    @Test
    void permitsAnotherPortalEntryOnlyAfterActualExit() {
        var guard = new NetherPortalReentryGuard();
        guard.beginTransfer();
        guard.finishTransfer(true);
        assertTrue(guard.blocksEntry(true));
        assertTrue(guard.blocksEntry(false), "One tick without contact does not prove portal exit");
        assertTrue(guard.blocksEntry(true), "Touching portal again cancels exit confirmation");
        assertTrue(guard.blocksEntry(false));
        assertFalse(guard.blocksEntry(false), "Two consecutive ticks outside release the guard");
        assertFalse(guard.blocksEntry(true), "New entry is eligible after exiting");
    }

    @Test
    void allowsRetryAfterFailedPortalTransfer() {
        var guard = new NetherPortalReentryGuard();
        guard.beginTransfer();
        assertTrue(guard.blocksEntry(true));

        guard.finishTransfer(false);
        assertFalse(guard.blocksEntry(true), "Failure must unlock portal to permit retry");
        guard.beginTransfer();
        assertTrue(guard.blocksEntry(true));
    }
}
