package org.allaymc.server.entity.component.player;

import org.allaymc.api.player.ClientState;

/**
 * Login and dimension-transfer readiness for portal activation.
 *
 * <p>This is independent of the portal dwell time: once an actual player's
 * client is IN_GAME and no dimension change is pending, creative/spectator
 * portals remain instant. Non-player or synthetic player entities keep their
 * normal portal behavior.</p>
 */
final class PortalEntryGate {
    private PortalEntryGate() {}

    static boolean mayTickPortal(boolean actualPlayer, ClientState clientState, boolean changingDimension) {
        return !actualPlayer || (clientState == ClientState.IN_GAME && !changingDimension);
    }
}
