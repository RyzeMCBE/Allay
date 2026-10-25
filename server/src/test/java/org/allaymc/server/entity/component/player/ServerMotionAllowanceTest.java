package org.allaymc.server.entity.component.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerMotionAllowanceTest {
    @Test
    void aMatchingImpulseCanBeConsumedOnlyOnce() {
        var allowance = new ServerMotionAllowance();
        allowance.record(0.42, 100);
        assertTrue(allowance.consume(0.34, 200));
        assertFalse(allowance.consume(0.42, 300));
    }

    @Test
    void missingExpiredOrReversedClockCannotAuthorizeMovement() {
        var allowance = new ServerMotionAllowance();
        assertFalse(allowance.consume(0.42, 100));
        allowance.record(0.42, 100);
        assertFalse(allowance.consume(0.42, 1_000_000_101L));
        allowance.record(0.42, 100);
        assertFalse(allowance.consume(0.42, 99));
    }

    @Test
    void excessiveOrUnrelatedMovementDoesNotConsumeThePendingImpulse() {
        var allowance = new ServerMotionAllowance();
        allowance.record(0.42, 100);
        assertFalse(allowance.consume(0.8, 200));
        assertFalse(allowance.consume(0.1, 300));
        assertFalse(allowance.consume(Double.NaN, 400));
        assertTrue(allowance.consume(0.42, 500));
    }

    @Test
    void aNewImpulseReplacesTheOldOneAndDownwardMotionClearsIt() {
        var allowance = new ServerMotionAllowance();
        allowance.record(0.8, 100);
        allowance.record(0.42, 200);
        assertFalse(allowance.consume(0.8, 300));
        assertTrue(allowance.consume(0.42, 400));
        allowance.record(0.42, 500);
        allowance.record(-0.2, 600);
        assertFalse(allowance.consume(0.42, 700));
    }
}
