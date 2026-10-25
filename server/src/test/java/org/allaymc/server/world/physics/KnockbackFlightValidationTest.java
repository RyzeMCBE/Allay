package org.allaymc.server.world.physics;

import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.player.GameMode;
import org.allaymc.api.player.Player;
import org.allaymc.api.world.Dimension;
import org.allaymc.server.entity.component.player.EntityPlayerPhysicsComponentImpl;
import org.allaymc.testutils.AllayTestExtension;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class KnockbackFlightValidationTest {
    @Test
    void airborneVelocityResetWithoutServerImpulseIsRejected() {
        var fixture = new Fixture(0.1);
        assertTrue(fixture.engine.isUnauthorizedFlightMovement(fixture.player, fixture.physics, new Vector3d(0, 0.4, 0)));
    }

    @Test
    void matchingServerImpulseIsAccepted() {
        var fixture = new Fixture(0.1);
        when(fixture.physics.consumeServerMotion(any())).thenReturn(true);
        assertFalse(fixture.engine.isUnauthorizedFlightMovement(fixture.player, fixture.physics, new Vector3d(0, 0.4, 0)));
    }

    @Test
    void gravityAfterKnockbackIsComparedWithTheLatestAcceptedMotion() {
        var fixture = new Fixture(0.42);
        when(fixture.physics.getLastMotion()).thenReturn(new Vector3d(0, 0.1, 0));
        assertFalse(fixture.engine.isUnauthorizedFlightMovement(fixture.player, fixture.physics, new Vector3d(0, 0.34, 0)));
    }

    @Test
    void groundedImpulseIsConsumedSoItCannotAuthorizeASecondAirJump() {
        var fixture = new Fixture(0);
        when(fixture.physics.isOnGround()).thenReturn(true);
        when(fixture.physics.consumeServerMotion(any())).thenReturn(true);
        assertFalse(fixture.engine.isUnauthorizedFlightMovement(fixture.player, fixture.physics, new Vector3d(0, 0.42, 0)));
        verify(fixture.physics).consumeServerMotion(any());
    }

    private static final class Fixture {
        private final EntityPlayer player = mock(EntityPlayer.class);
        private final EntityPlayerPhysicsComponentImpl physics = mock(EntityPlayerPhysicsComponentImpl.class);
        private final AllayEntityPhysicsEngine engine = new AllayEntityPhysicsEngine(mock(Dimension.class));

        private Fixture(double previousVertical) {
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getController()).thenReturn(mock(Player.class));
            when(physics.getMotion()).thenReturn(new Vector3d(0, previousVertical, 0));
        }
    }
}
