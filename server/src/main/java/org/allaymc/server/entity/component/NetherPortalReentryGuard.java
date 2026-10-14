package org.allaymc.server.entity.component;

/**
 * Keeps a Nether portal from immediately reversing a dimension transfer.
 *
 * <p>The ordinary 300-tick portal cooldown is time-based. If a creative player is
 * still standing in the destination portal (for instance while a client loading
 * screen is visible), the cooldown alone would eventually allow another
 * teleport. This guard requires the entity to actually leave the portal before
 * a new traversal is allowed. It also stays armed while the asynchronous
 * destination load is pending.</p>
 */
final class NetherPortalReentryGuard {
    private static final int EXIT_CONFIRMATION_TICKS = 2;

    private boolean transferPending;
    private boolean requiresExit;
    private int outsideTicks;

    void beginTransfer() {
        transferPending = true;
        requiresExit = true;
        outsideTicks = 0;
    }

    void finishTransfer(boolean success) {
        transferPending = false;
        if (!success) {
            // A rejected transfer may be retried without forcing the entity
            // to step out of the source portal first.
            requiresExit = false;
            outsideTicks = 0;
        }
    }

    boolean blocksEntry(boolean touchingPortal) {
        if (transferPending) {
            return true;
        }
        if (!requiresExit) {
            return false;
        }
        if (touchingPortal) {
            outsideTicks = 0;
        } else if (++outsideTicks >= EXIT_CONFIRMATION_TICKS) {
            requiresExit = false;
            outsideTicks = 0;
        }
        return requiresExit;
    }
}
