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
        assertEquals(Set.of(0, 1, 2), DimensionStorageInspector.detectDimensionIds(world));
        assertEquals("Custom End coordinates", Files.readString(
                DimensionSpawnMetadata.sidecarFolder(world, 2).resolve("level.dat")));

        assertTrue(DimensionLayoutTransition.ensureLayout(root, "world", "SEPARATE", locations));
        assertEquals("Custom End coordinates", Files.readString(end.resolve("level.dat")));
        assertEquals("Custom Nether coordinates", Files.readString(nether.resolve("level.dat")));
        Files.writeString(end.resolve("level.dat"), "Updated custom End coordinates");
        assertTrue(DimensionLayoutTransition.ensureLayout(root, "world", "SHARED", locations));
        assertEquals("Updated custom End coordinates", Files.readString(
                DimensionSpawnMetadata.sidecarFolder(world, 2).resolve("level.dat")));
        try (var backups = Files.list(root.resolve(".dimension-layout-backups").resolve("world"))) {
            assertEquals(4, backups.count());
        }
    }


    @Test
    void importsAnOverworldAuthoredEndDirectlyIntoSharedStorage() throws Exception {
        var world = root.resolve("shared-primary");
        var end = root.resolve("shared-primary-end");
        Files.createDirectories(world.resolve("db"));
        try (var db = new Iq80DBFactory().open(world.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(new byte[]{0,0,0,0,0,0,0,0,44}, new byte[]{1});
        }
        Files.createDirectories(end.resolve("db"));
        Files.writeString(end.resolve("level.dat"), "My imported End spawn");
        try (var db = new Iq80DBFactory().open(end.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(new byte[]{1,0,0,0,2,0,0,0,44}, new byte[]{2});
        }
        var paths = Map.of(0, world, 1, root.resolve("shared-primary-nether"), 2, end);
        assertTrue(DimensionLayoutTransition.ensureLayout(root, "shared-primary", "SHARED", paths));
        assertEquals(Set.of(0, 2), DimensionStorageInspector.detectDimensionIds(world));
        assertFalse(Files.exists(end));
        assertEquals("My imported End spawn", Files.readString(
                DimensionSpawnMetadata.sidecarFolder(world, 2).resolve("level.dat")));
        assertTrue(Files.isDirectory(root.resolve(".dimension-backups").resolve("shared-primary")));
    }

}
