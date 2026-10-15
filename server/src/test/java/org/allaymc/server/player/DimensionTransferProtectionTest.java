package org.allaymc.server.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionTransferProtectionTest {
    private static final long START = 1_000_000_000L;

    @Test
    void protectsThroughThirtySecondLoadingScreen() {
        var gate = new DimensionTransferProtection();
        gate.begin(START);
        assertTrue(gate.isProtected(START + 1_000_000_000L));
        assertFalse(gate.movementAccepted(START + 29_000_000_000L));
        assertTrue(gate.isProtected(START + 30_000_000_000L));
    }

    @Test
    void onlyValidatedMovementAfterClientScreenEndReleases() {
        var gate = new DimensionTransferProtection();
        gate.begin(START);
        assertFalse(gate.movementAccepted(START + 100_000_000L),
                "Heartbeats/movement before END cannot remove the protection");
        gate.screenEnded(START + 30_000_000_000L);
        assertTrue(gate.isProtected(START + 30_000_000_000L));
        assertTrue(gate.movementAccepted(START + 30_500_000_000L));
        assertFalse(gate.isProtected(START + 30_500_000_000L));
    }

    @Test
    void standingStillAfterLoadingCannotKeepImmunity() {
        var gate = new DimensionTransferProtection();
        gate.begin(START);
        gate.screenEnded(START + 30_000_000_000L);
        assertTrue(gate.isProtected(START + 32_999_000_000L));
        assertFalse(gate.isProtected(START + 33_000_000_000L));
    }

    @Test
    void missingLoadingScreenNotificationDoesNotGiveInfiniteProtection() {
        var gate = new DimensionTransferProtection();
        gate.begin(START);
        assertTrue(gate.isProtected(START + 64_999_000_000L));
        assertFalse(gate.isProtected(START + 65_000_000_000L));
        assertFalse(gate.movementAccepted(START + 65_100_000_000L));
    }

    @Test
    void secondDimensionChangeResetsTimerAndReadiness() {
        var gate = new DimensionTransferProtection();
        gate.begin(START);
        gate.screenEnded(START + 30_000_000_000L);
        assertTrue(gate.movementAccepted(START + 30_400_000_000L));
        assertFalse(gate.isProtected(START + 30_500_000_000L));

        gate.begin(START + 40_000_000_000L);
        assertTrue(gate.isProtected(START + 50_000_000_000L));
        assertFalse(gate.movementAccepted(START + 50_000_000_000L));
        assertTrue(gate.isProtected(START + 50_000_000_000L));
    }

    @Test
    void concurrentScreenEndAndDamageChecksRemainSafe() throws InterruptedException {
        var gate = new DimensionTransferProtection();
        gate.begin(START);
        var worker = Thread.ofVirtual().start(() -> gate.screenEnded(START + 30_000_000_000L));
        worker.join();
        assertTrue(gate.isProtected(START + 31_000_000_000L));
        assertTrue(gate.movementAccepted(START + 31_100_000_000L));
        assertFalse(gate.isProtected(START + 31_100_000_000L));
    }
}
