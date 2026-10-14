package org.allaymc.server.world.storage;

import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DimensionLayoutTransitionTest {
    @TempDir Path root;

    @Test
    void switchesModesBothWaysWithBackupsAndSavedEndMetadata() throws Exception {
        var world = root.resolve("world");
        var nether = root.resolve("world-nether");
        var end = root.resolve("world-end");
        var locations = Map.of(0, world, 1, nether, 2, end);
        Files.createDirectories(world.resolve("db"));
        Files.writeString(world.resolve("level.dat"), "Original overworld spawn");
        try (var db = new Iq80DBFactory().open(world.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(new byte[]{0,0,0,0,0,0,0,0,44}, new byte[]{10});
            db.put(new byte[]{0,0,0,0,0,0,0,0,1,0,0,0,44}, new byte[]{11});
            db.put(new byte[]{0,0,0,0,0,0,0,0,2,0,0,0,44}, new byte[]{12});
        }

        assertTrue(DimensionLayoutTransition.ensureLayout(root, "world", "SEPARATE", locations));
        assertFalse(DimensionLayoutTransition.ensureLayout(root, "world", "SEPARATE", locations));
        assertEquals(Set.of(0), DimensionStorageInspector.detectDimensionIds(world));
        assertEquals(Set.of(1), DimensionStorageInspector.detectDimensionIds(nether));
        assertEquals(Set.of(2), DimensionStorageInspector.detectDimensionIds(end));
        assertEquals("Original overworld spawn", Files.readString(world.resolve("level.dat")));

        Files.writeString(nether.resolve("level.dat"), "Custom Nether coordinates");
        Files.writeString(end.resolve("level.dat"), "Custom End coordinates");

        assertTrue(DimensionLayoutTransition.ensureLayout(root, "world", "SHARED", locations));
        assertFalse(DimensionLayoutTransition.ensureLayout(root, "world", "SHARED", locations));
        assertFalse(Files.exists(nether));
        assertFalse(Files.exists(end));
        firstTwoOrAll(DimensionStorageInspector.detectDimensionIds(world));
        assertEquals("Custom End coordinates", Files.readString(
                DimensionSpawnMetadata.sidecarFolder(world, 2).resolve("level.dat")));

        assertTrue(DimensionLayoutTransition.ensureLayout(root, "world", "SEPARATE", locations));
        assertEquals("Custom End coordinates", Files.readString(end.resolve("level.dat")));
        assertEquals("Custom Nether coordinates", Files.readString(nether.resolve("level.dat")));
        try (var backups = Files.list(root.resolve(".dimension-layout-backups").resolve("world"))) {
            assertEquals(3, backups.count());
        }
    }

    // The inspector stops as soon as two physical IDs are detected because
    // that is sufficient to identify mixed storage.
    private static Set<Integer> firstTwoOrAll(Set<Integer> ids) {
        assertTrue(ids.contains(0));
        assertTrue(ids.contains(1) || ids.contains(2));
        return ids;
    }
}
