package org.allaymc.server.utils;

import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.block.property.enums.MinecraftVerticalHalf;
import org.allaymc.api.block.property.type.BlockPropertyTypes;
import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.allaymc.api.blockentity.type.BlockEntityTypes;
import org.allaymc.api.entity.EntityInitInfo;
import org.allaymc.api.entity.type.EntityTypes;
import org.allaymc.api.item.enchantment.EnchantmentTypes;
import org.allaymc.api.item.type.ItemTypes;
import org.allaymc.api.server.Server;
import org.allaymc.api.utils.NBTIO;
import org.allaymc.server.network.ProtocolInfo;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import org.cloudburstmc.nbt.NbtMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author daoge_cmd
 */
@ExtendWith(AllayTestExtension.class)
class AllayNBTIOTest {
    @Test
    void unsupportedTilesAreCountedByTypeAndTheirDataIsPreserved() {
        var nbtio = new AllayNBTIO();
        var dimension = Server.getInstance().getWorldPool().getDefaultWorld().getOverWorld();
        var original = NbtMap.builder().putString("id","FutureTestTile")
                .putInt("x",1).putInt("y",65).putInt("z",2).putInt("CustomData",42).build();
        for (int i=0;i<3;i++) {
            assertEquals(42,nbtio.fromBlockEntityNBT(dimension,original).saveNBT().getInt("CustomData"));
        }
        assertEquals(java.util.Map.of("FutureTestTile",3L),nbtio.unsupportedBlockEntityCounts());
    }

    @Test
    void daylightDetectorIsRecognizedAndKeepsItsImportedData() {
        var nbtio = new AllayNBTIO();
        var dimension = Server.getInstance().getWorldPool().getDefaultWorld().getOverWorld();
        var original = NbtMap.builder().putString("id","DaylightDetector")
                .putInt("x",1).putInt("y",65).putInt("z",2)
                .putByte("isMovable", (byte) 1).putInt("CustomSensorData", 27)
                .putCompound("ImportedData",NbtMap.builder().putInt("value",42).build()).build();
        var tile = nbtio.fromBlockEntityNBT(dimension,original);
        assertEquals(BlockEntityTypes.DAYLIGHT_DETECTOR,tile.getBlockEntityType());
        assertEquals("DaylightDetector",tile.saveNBT().getString("id"));
        assertEquals(original.getCompound("ImportedData"),tile.saveNBT().getCompound("ImportedData"));
        assertEquals(27, tile.saveNBT().getInt("CustomSensorData"));
        assertEquals(tile.saveNBT(), nbtio.fromBlockEntityNBT(dimension, tile.saveNBT()).saveNBT());
        assertTrue(nbtio.unsupportedBlockEntityCounts().isEmpty());
    }

    @Test
    void testFromBlockStateNBT() {
        var block1 = BlockTypes.BAMBOO.getDefaultState();
        var nbt = block1.getBlockStateNBT();
        var block2 = NBTIO.getAPI().fromBlockStateNBT(nbt);
        assertEquals(block1, block2);
    }

    /**
     * PocketMine/Altay blok durumlarını kendi sürüm numarasıyla (1.26.50) yazıyor, Allay'inki daha eski. Sürüm daha
     * yeni diye tanıdık durumu bilinmeyen bloğa çevirmek PM'den gelen saksıdaki bitkiyi siliyordu; chunk yolu gibi
     * önce tanımaya çalışmalı.
     */
    @Test
    void testFromBlockStateNBTAcceptsNewerVersionOfKnownState() {
        var state = BlockTypes.BAMBOO.getDefaultState();
        var nbt = state.getBlockStateNBT().toBuilder().putInt("version", ProtocolInfo.BLOCK_STATE_VERSION_NUM + 1).build();
        assertEquals(state, NBTIO.getAPI().fromBlockStateNBT(nbt));
    }

    /**
     * Daha yeni sürümün tanıdık türe eklediği özellik ya da geçersiz değer istisna atmamalı; API sözleşmesi gereği
     * bilinmeyen blok dönmeli.
     */
    @Test
    void testFromBlockStateNBTReturnsUnknownForUnrecognizedState() {
        var newerVersion = ProtocolInfo.BLOCK_STATE_VERSION_NUM + 1;
        var extraProperty = BlockTypes.OAK_FENCE.getDefaultState().getBlockStateNBT().toBuilder()
                .putCompound("states", NbtMap.builder().putByte("minecraft:gears_test_future_state", (byte) 1).build())
                .putInt("version", newerVersion)
                .build();
        var invalidValue = BlockTypes.BAMBOO.getDefaultState().getBlockStateNBT().toBuilder()
                .putCompound("states", NbtMap.builder().putString("bamboo_leaf_size", "gears_invalid").build())
                .build();

        assertEquals(BlockTypes.UNKNOWN.getDefaultState(), NBTIO.getAPI().fromBlockStateNBT(extraProperty));
        assertEquals(BlockTypes.UNKNOWN.getDefaultState(), NBTIO.getAPI().fromBlockStateNBT(invalidValue));
    }

    /**
     * Saksıdaki bitki dünya verisinin parçası: tanınmayan bitki durumu yüklenip kaydedilince özgün NBT aynen geri
     * yazılmalı.
     */
    @Test
    void testFlowerPotKeepsUnrecognizedPlant() {
        var dimension = Server.getInstance().getWorldPool().getDefaultWorld().getOverWorld();
        var unrecognizedPlant = NbtMap.builder()
                .putString("name", "minecraft:gears_test_future_plant")
                .putCompound("states", NbtMap.EMPTY)
                .putInt("version", ProtocolInfo.BLOCK_STATE_VERSION_NUM + 1)
                .build();
        var pot = BlockEntityTypes.FLOWER_POT.createBlockEntity(
                BlockEntityInitInfo.builder().dimension(dimension).pos(0, 0, 0).build()
        );
        var nbt = pot.saveNBT().toBuilder().putCompound("PlantBlock", unrecognizedPlant).build();

        var loaded = NBTIO.getAPI().fromBlockEntityNBT(dimension, nbt);

        assertEquals(unrecognizedPlant, loaded.saveNBT().getCompound("PlantBlock"));
    }

    /**
     * A cross-server world may already have a current/newer palette version
     * but still encode minecraft:vertical_half using a numeric NBT type.
     * Only 0 and 1 are recognized; both top and bottom must remain intact.
     */
    @Test
    void testNumericVerticalHalfInCurrentVersionSlabPalettes() {
        for (var slab : List.of(BlockTypes.SMOOTH_STONE_SLAB, BlockTypes.SMOOTH_STONE_DOUBLE_SLAB,
                BlockTypes.OAK_SLAB, BlockTypes.OAK_DOUBLE_SLAB)) {
            for (int half = 0; half <= 1; half++) {
                var expected = slab.getDefaultState().setPropertyValue(
                        BlockPropertyTypes.MINECRAFT_VERTICAL_HALF,
                        half == 0 ? MinecraftVerticalHalf.BOTTOM : MinecraftVerticalHalf.TOP);
                for (int tagType = 0; tagType < 4; tagType++) {
                    var states = expected.getBlockStateNBT().getCompound("states").toBuilder();
                    switch (tagType) {
                        case 0 -> states.putByte("minecraft:vertical_half", (byte) half);
                        case 1 -> states.putShort("minecraft:vertical_half", (short) half);
                        case 2 -> states.putInt("minecraft:vertical_half", half);
                        case 3 -> states.putLong("minecraft:vertical_half", half);
                        default -> throw new AssertionError("Unexpected NBT type");
                    }
                    var stored = expected.getBlockStateNBT().toBuilder()
                            .putCompound("states", states.build())
                            .putInt("version", ProtocolInfo.BLOCK_STATE_VERSION_NUM + 1)
                            .build();
                    assertEquals(expected, NBTIO.getAPI().fromBlockStateNBT(stored),
                            slab.getIdentifier() + " half=" + half + " nbtType=" + tagType);
                }
            }
        }
    }

    @Test
    void testOutOfRangeSlabHalfRemainsUnknown() {
        var slab = BlockTypes.SMOOTH_STONE_SLAB.getDefaultState();
        var nbt = slab.getBlockStateNBT().toBuilder()
                .putCompound("states", slab.getBlockStateNBT().getCompound("states").toBuilder()
                        .putInt("minecraft:vertical_half", 2)
                        .build())
                .putInt("version", ProtocolInfo.BLOCK_STATE_VERSION_NUM + 1)
                .build();
        assertEquals(BlockTypes.UNKNOWN.getDefaultState(), NBTIO.getAPI().fromBlockStateNBT(nbt));
    }

    @Test
    void testFromItemStackNBT() {
        var item1 = ItemTypes.DIAMOND.createItemStack(1, 1);
        item1.addEnchantment(EnchantmentTypes.SHARPNESS, 1);
        item1.setLore(List.of("test"));
        item1.setCustomName("test");
        var nbt = item1.saveNBT();
        var item2 = NBTIO.getAPI().fromItemStackNBT(nbt);
        assertTrue(item1.canMerge(item2));
    }

    @Test
    void testFromEntityNBT() {
        var entity1 = EntityTypes.VILLAGER_V2.createEntity(
                EntityInitInfo
                        .builder()
                        .loc(Server.getInstance().getWorldPool().getGlobalSpawnPoint())
                        .build()
        );
        var nbt1 = entity1.saveNBT();
        var entity2 = NBTIO.getAPI().fromEntityNBT(Server.getInstance().getWorldPool().getGlobalSpawnPoint().dimension(), nbt1);
        var nbt2 = entity2.saveNBT();
        assertEquals(nbt1, nbt2);
    }

    @Test
    void testPocketMineMobSpawnerNbtRoundTrip() {
        var dimension = Server.getInstance().getWorldPool().getDefaultWorld().getOverWorld();
        var original = NbtMap.builder()
                .putString("id", "MobSpawner")
                .putInt("x", 12).putInt("y", 68).putInt("z", 9)
                .putShort("Delay", (short) 35)
                .putShort("MinSpawnDelay", (short) 200)
                .putShort("MaxSpawnDelay", (short) 800)
                .putShort("SpawnCount", (short) 4)
                .putString("EntityIdentifier", "minecraft:zombie")
                .putString("PocketMineCustomTag", "keep-this-data")
                .build();

        var loaded = NBTIO.getAPI().fromBlockEntityNBT(dimension, original);
        org.junit.jupiter.api.Assertions.assertNotNull(loaded);
        var stored = loaded.saveNBT();
        assertEquals("MobSpawner", stored.getString("id"));
        assertEquals((short) 35, stored.getShort("Delay"));
        assertEquals("minecraft:zombie", stored.getString("EntityIdentifier"));
        assertEquals("keep-this-data", stored.getString("PocketMineCustomTag"));
    }

    @Test
    void testUnknownTileEntityNbtRoundTrip() {
        var dimension = Server.getInstance().getWorldPool().getDefaultWorld().getOverWorld();
        var original = NbtMap.builder()
                .putString("id", "PocketMineCustomTile")
                .putInt("x", 5).putInt("y", 70).putInt("z", 6)
                .putString("CustomPluginData", "must-survive")
                .build();

        var loaded = NBTIO.getAPI().fromBlockEntityNBT(dimension, original);
        org.junit.jupiter.api.Assertions.assertNotNull(loaded);
        var stored = loaded.saveNBT();
        assertEquals("PocketMineCustomTile", stored.getString("id"));
        assertEquals("must-survive", stored.getString("CustomPluginData"));
    }

    @Test
    void testFromBlockEntityNBT() {
        var blockEntity1 = BlockEntityTypes.CHEST.createBlockEntity(
                BlockEntityInitInfo
                        .builder()
                        .dimension(Server.getInstance().getWorldPool().getDefaultWorld().getOverWorld())
                        .pos(0, 0, 0)
                        .build()
        );
        var nbt1 = blockEntity1.saveNBT();
        var blockEntity2 = NBTIO.getAPI().fromBlockEntityNBT(Server.getInstance().getWorldPool().getDefaultWorld().getOverWorld(), nbt1);
        var nbt2 = blockEntity2.saveNBT();
        assertEquals(nbt1, nbt2);
    }

    /**
     * PocketMine eklentileri varlik kimligine PHP sinif adi yazabiliyor
     * (ornegin {@code brokiem\snpc\entity\CustomHuman}). Ters bolu
     * {@link org.allaymc.api.utils.identifier.Identifier} icin gecersiz oldugundan
     * kurucu istisna atiyordu; istisna cagirana kadar cikip o chunk'taki BUTUN
     * varliklarin okunmasini iptal ediyordu. Bicimsiz kimlik, bilinmeyen tip gibi
     * {@code null} donmeli ki cagiran yalnizca o varligi atlasin.
     */
    @Test
    void testFromEntityNBTReturnsNullForMalformedIdentifier() {
        var nbt = NbtMap.builder()
                .putString("identifier", "brokiem\\snpc\\entity\\CustomHuman")
                .build();

        var dimension = Server.getInstance().getWorldPool().getGlobalSpawnPoint().dimension();
        assertNull(NBTIO.getAPI().fromEntityNBT(dimension, nbt));
    }
}
