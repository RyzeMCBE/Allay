package org.allaymc.server.item.recipe;

import org.allaymc.api.utils.DyeColor;
import org.allaymc.api.world.data.FireworkType;
import org.allaymc.api.world.explosion.FireworkExplosion;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FireworkNbtCompatibilityTest {
    @Test
    void everyNativeShapeAndItsColorsSurviveSavingAndLoading() {
        for (var type : FireworkType.values()) {
            var effect = new FireworkExplosion(type,List.of(DyeColor.ORANGE),List.of(DyeColor.YELLOW),true,true);
            assertEquals(effect,FireworkExplosion.fromNBT(effect.saveNBT()));
        }
    }

    @Test
    void legacyShortShapeRemainsSupported() {
        var effect = new FireworkExplosion(FireworkType.STAR,List.of(DyeColor.ORANGE),List.of(),false,true);
        var oldNbt = effect.saveNBT().toBuilder().putShort("FireworkType",(short)2).build();
        assertEquals(effect,FireworkExplosion.fromNBT(oldNbt));
    }

    @Test
    void malformedShapeAndColorIdsHaveSafeDefaults() {
        var nbt = NbtMap.builder().putByte("FireworkType",(byte)127)
                .putByteArray("FireworkColor",new byte[]{-1,127}).build();
        var effect = FireworkExplosion.fromNBT(nbt);
        assertEquals(FireworkType.SMALL_SPHERE,effect.type());
        assertEquals(List.of(DyeColor.WHITE),effect.colors());
    }
}
