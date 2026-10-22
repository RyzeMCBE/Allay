package org.allaymc.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ServerSettingsTest {

    @Test
    void parallelDimensionTickingIsOptInForNewConfigurations() {
        assertFalse(new ServerSettings.WorldSettings().tickDimensionInParallel());
        assertFalse(new ServerSettings.WorldSettings().dimensionTransitionDiagnostics());
    }
}
