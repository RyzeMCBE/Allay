package org.allaymc.server.player;

/**
 * Server-side damage protection during a Bedrock dimension loading screen.
 *
 * <p>The first destination chunks and server dimension ACK may be sent
 * long before the Bedrock client dismisses its loading screen. This gate
 * does not attempt to dismiss the screen or delay chunks.
 *
 * <p>Damage protection is released at the first validated movement AFTER
 * the client reports END_LOADING_SCREEN. If the player stays still, it
 * expires three seconds after END to prevent permanent PvP immunity.
 * A separate hard limit also covers missing/malformed client END messages.
 *
 * <p>Network receives, world ticks and dimension change callbacks can run
 * on different threads. Access is synchronized.
 */
final class DimensionTransferProtection {
    static final long AFTER_SCREEN_END_NANOS = 3_000_000_000L;
    static final long MAX_PROTECTION_NANOS = 65_000_000_000L;

    private boolean active;
    private long beganAtNanos;
    private long screenEndedAtNanos;

    synchronized void begin(long nowNanos) {
        active = true;
        beganAtNanos = nowNanos;
        screenEndedAtNanos = 0;
    }

    synchronized void screenEnded(long nowNanos) {
        if (active && screenEndedAtNanos == 0) {
            screenEndedAtNanos = nowNanos;
        }
    }

    synchronized boolean movementAccepted(long nowNanos) {
        if (!isProtected(nowNanos) || screenEndedAtNanos == 0) {
            return false;
        }
        active = false;
        return true;
    }

    synchronized boolean isProtected(long nowNanos) {
        if (!active) {
            return false;
        }
        if (nowNanos - beganAtNanos >= MAX_PROTECTION_NANOS ||
            (screenEndedAtNanos != 0 &&
                nowNanos - screenEndedAtNanos >= AFTER_SCREEN_END_NANOS)) {
            active = false;
            return false;
        }
        return true;
    }
}
