package org.allaymc.server.registry.loader;

import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecipeNbtDecodeTest {
    @Test
    void treatsBedrockZeroPlaceholderAsNoExtraData() {
        assertEquals(NbtMap.EMPTY, RecipeJsonUtils.parseRecipeOutputNbt("AAAAAAAAAAAAAA=="));
        assertEquals(NbtMap.EMPTY, RecipeJsonUtils.parseRecipeOutputNbt(""));
    }

    @Test
    void decodesReal12660BannerNetworkNbt() {
        // Captured from the official BDS 1.26.60 minecraft:black_banner recipe.
        var tag = RecipeJsonUtils.parseRecipeOutputNbt("//8BCgAAAwQAVHlwZQAAAAAAAAAAAAAAAAA=");
        assertNotNull(tag);
        assertEquals(0, tag.getInt("Type"));
    }
}
