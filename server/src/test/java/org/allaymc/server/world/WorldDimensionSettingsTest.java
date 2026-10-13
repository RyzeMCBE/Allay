package org.allaymc.server.world;

import org.allaymc.api.utils.identifier.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class WorldDimensionSettingsTest {

    @Test
    void legacyWorldLayoutDefaultsToSharedStorage() {
        var settings = new AllayWorldPool.WorldSettings.WorldSetting(
                "LEVELDB", false, new LinkedHashMap<>()
        );
        var normalized = settings.normalized();
        assertEquals("SHARED", normalized.dimensionStorageMode());
    }

    @Test
    void settingsPreserveStorageModeAndDisabledDimensionOptions() {
        var end = new AllayWorldPool.WorldSettings.WorldSetting.DimensionSetting("VOID", "");
        end.enabled(false);
        end.path("end");
        var dimensions = new LinkedHashMap<String, AllayWorldPool.WorldSettings.WorldSetting.DimensionSetting>();
        dimensions.put("minecraft:the_end", end);
        var settings = new AllayWorldPool.WorldSettings.WorldSetting("LEVELDB", false, dimensions);
        settings.dimensionStorageMode("separate");
        var normalized = settings.normalized();
        assertEquals("SEPARATE", normalized.dimensionStorageMode());
        var normalizedEnd = normalized.normalizedDimensions().get(new Identifier("minecraft:the_end"));
        assertNotNull(normalizedEnd);
        assertFalse(normalizedEnd.enabled());
        assertEquals("end", normalizedEnd.path());
    }

    @Test
    void defaultDimensionIsEnabled() {
        var settings = new AllayWorldPool.WorldSettings.WorldSetting.DimensionSetting("FLAT", "");
        assertTrue(settings.enabled());
        assertTrue(settings.enableLightCalculation());
        assertNull(settings.path());
    }
}
