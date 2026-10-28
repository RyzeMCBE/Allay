package org.allaymc.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSettingsTest {

    @Test
    void nethernetSharesTheExistingBedrockPortByDefault() {
        assertTrue(new ServerSettings().networkSettings().nethernetSharedPort());
    }

    @Test
    void playerPacketDiagnosticsAreOptIn() {
        assertFalse(new ServerSettings().networkSettings().playerPacketDiagnostics().enabled());
    }

    @Test
    void parallelDimensionTickingIsOptInForNewConfigurations() {
        assertFalse(new ServerSettings.WorldSettings().tickDimensionInParallel());
        assertFalse(new ServerSettings.WorldSettings().dimensionTransitionDiagnostics());
        assertFalse(new ServerSettings.WorldSettings().parallelTickDiagnostics());
    }
}

