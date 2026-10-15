package org.allaymc.server.world.storage;

import org.allaymc.api.world.dimension.DimensionType;
import org.allaymc.api.world.storage.WorldStorage;
import org.allaymc.api.world.World;
import org.allaymc.api.world.WorldData;
import org.allaymc.api.world.chunk.Chunk;
import org.allaymc.api.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DimensionWorldStorageTest {
    @Test
    void routesChunksEntitiesAndWorldMetadata() {
        var overworld = mock(DimensionType.class);
        var end = mock(DimensionType.class);
        var primary = mock(WorldStorage.class);
        var endStorage = mock(WorldStorage.class);
        var stores = new LinkedHashMap<DimensionType, WorldStorage>();
        stores.put(overworld, primary);
        stores.put(end, endStorage);
        var routed = new DimensionWorldStorage(primary, stores);
        var world = mock(World.class);
        routed.setWorld(world);
        verify(primary).setWorld(world);
        verify(endStorage).setWorld(world);
        var chunk = mock(Chunk.class);
        when(chunk.getDimensionType()).thenReturn(end);
        routed.writeChunkSync(chunk);
        verify(endStorage).writeChunkSync(chunk);
        verify(primary, never()).writeChunkSync(chunk);
        routed.containChunk(2, 5, overworld);
        verify(primary).containChunk(2, 5, overworld);
        routed.readEntitiesSync(3, 4, end);
        verify(endStorage).readEntitiesSync(3, 4, end);
        var data = mock(WorldData.class);
        routed.writeWorldData(data);
        verify(primary).writeWorldData(data);
        verify(endStorage, never()).writeWorldData(any());
        routed.shutdown();
        verify(primary).shutdown();
        verify(endStorage).shutdown();
    }

    @Test
    void rejectsDuplicateStorageAndUnregisteredDimensions() {
        var overworld = mock(DimensionType.class);
        var end = mock(DimensionType.class);
        var storage = mock(WorldStorage.class);
        var stores = new LinkedHashMap<DimensionType, WorldStorage>();
        stores.put(overworld, storage);
        stores.put(end, storage);
        assertThrows(IllegalArgumentException.class, () -> new DimensionWorldStorage(storage, stores));
        var routed = new DimensionWorldStorage(storage, Map.of(overworld, storage));
        assertThrows(IllegalArgumentException.class, () -> routed.containChunk(0, 0, end));
    }

    @Test
    void inspectorRecognizesOverworldAndEndChunkPrefixes() {
        assertEquals(0, DimensionStorageInspector.findDimensionId(new byte[]{0,0,0,0,0,0,0,0,44}));
        assertEquals(2, DimensionStorageInspector.findDimensionId(new byte[]{0,0,0,0,0,0,0,0,2,0,0,0,44}));
        assertNull(DimensionStorageInspector.findDimensionId(new byte[]{1,2,3}));
    }
}
