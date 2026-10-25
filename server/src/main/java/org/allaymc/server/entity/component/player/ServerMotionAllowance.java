package org.allaymc.server.entity.component.player;

/** Tracks one pending upward impulse sent by the server, independently of delayed client movement. */
final class ServerMotionAllowance {
    private static final long TIMEOUT_NANOS = 1_000_000_000L;
    private double vertical;
    private long issuedAt;
    private boolean pending;

    void record(double vertical, long now) {
        this.vertical = vertical;
        this.issuedAt = now;
        this.pending = Double.isFinite(vertical) && vertical > 0;
    }

    boolean consume(double reportedVertical, long now) {
        if (!pending) return false;
        if (now - issuedAt > TIMEOUT_NANOS || now < issuedAt) {
            pending = false;
            return false;
        }
        // Match the sent impulse, allowing one client gravity step and minor rounding differences.
        if (!Double.isFinite(reportedVertical) || reportedVertical <= 0
                || reportedVertical < vertical - 0.1 || reportedVertical > vertical + 0.05) {
            return false;
        }
        pending = false;
        return true;
    }
}
