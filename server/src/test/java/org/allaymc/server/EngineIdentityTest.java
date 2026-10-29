package org.allaymc.server;

import org.allaymc.api.AllayAPI;
import org.allaymc.server.plugin.SimplePluginDescriptor;
import org.allaymc.server.utils.JSONUtils;
import org.allaymc.server.utils.Utils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EngineIdentityTest {
    @Test
    void keepsLegacyApiClassesAndBrand() {
        assertEquals("Engine", EngineIdentity.NAME);
        assertNotNull(AllayAPI.class);
        assertEquals("Engine Bedrock Server", new ServerSettings.GenericSettings().motd());
    }

    @Test
    void acceptsAnUnchangedAllayPluginJson() {
        var descriptor = JSONUtils.from("""
                {"name":"LegacyPlugin","entrance":"example.LegacyPlugin",
                 "version":"1.0.0","authors":["Example"],"api_version":"^0.30.0"}
                """, SimplePluginDescriptor.class);
        Utils.checkDescriptorValid(descriptor);
        assertEquals("LegacyPlugin", descriptor.getName());
        assertEquals("^0.30.0", descriptor.getAPIVersion());
    }
}
