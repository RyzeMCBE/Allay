package org.allaymc.server.entity.component.item;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class FireworkLifecycleTest {
    @Test
    void explosionIsSentOnceAndRemovalWaitsForThreeMoreTicks() {
        var rocket = mock(EntityFireworksRocketBaseComponentImpl.class, CALLS_REAL_METHODS);
        doNothing().when(rocket).broadcastState();
        doNothing().when(rocket).explode();
        doNothing().when(rocket).remove();
        rocket.setExistenceTicks(1);

        rocket.tickFirework();
        verify(rocket, never()).explode();
        rocket.tickFirework();
        verify(rocket).explode();
        verify(rocket, never()).remove();
        rocket.tickFirework();
        rocket.tickFirework();
        verify(rocket, never()).remove();
        rocket.tickFirework();
        rocket.tickFirework();
        verify(rocket, times(1)).explode();
        verify(rocket, times(1)).remove();
        verify(rocket).setPersistent(false);
    }
}
