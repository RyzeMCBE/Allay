package org.allaymc.server.world.storage;

import org.allaymc.api.utils.AllayNBTUtils;
import org.allaymc.api.world.dimension.DimensionTypes;
import org.allaymc.api.world.storage.WorldStorageException;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(AllayTestExtension.class)
class DimensionSpawnMetadataTest {
    @TempDir Path temp;

    @Test
    void preservesExactSavedCustomEndSpawnCoordinates() throws Exception {
        var world = temp.resolve("world");
        var folder = DimensionSpawnMetadata.sidecarFolder(world, 2);
        Files.createDirectories(folder);
        var nbt = NbtMap.builder()
                .putInt("SpawnX", -27)
                .putInt("SpawnY", 85)
                .putInt("SpawnZ", 138)
                .build();
        var payload = AllayNBTUtils.nbtToBytesLE(nbt);
        var bytes = ByteBuffer.allocate(8 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(10)
                .putInt(payload.length)
                .put(payload)
                .array();
        Files.write(folder.resolve("level.dat"), bytes);
        var spawn = DimensionSpawnMetadata.readSavedSpawn(world, DimensionTypes.THE_END);
        assertNotNull(spawn);
        assertEquals(-27, spawn.x());
        assertEquals(85, spawn.y());
        assertEquals(138, spawn.z());
    }

    @Test
    void refusesDamagedDimensionSpawnMetadata() throws Exception {
        var world = temp.resolve("world");
        var folder = DimensionSpawnMetadata.sidecarFolder(world, 2);
        Files.createDirectories(folder);
        Files.write(folder.resolve("level.dat"), new byte[]{1,2,3});
        assertThrows(WorldStorageException.class,
                () -> DimensionSpawnMetadata.readSavedSpawn(world, DimensionTypes.THE_END));
    }
}
