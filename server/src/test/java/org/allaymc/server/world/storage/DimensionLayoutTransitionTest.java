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

    @Test
    void refusesOverlappingDimensionDataAndKeepsBothSourcesIntact() throws Exception {
        var primary = root.resolve("conflict-world");
        var nether = root.resolve("conflict-nether");
        Files.createDirectories(primary.resolve("db"));
        Files.createDirectories(nether.resolve("db"));
        byte[] sharedNetherKey = {0,0,0,0,0,0,0,0,1,0,0,0,44};
        byte[] separateNetherKey = {1,0,0,0,0,0,0,0,1,0,0,0,44};
        try (var db = new Iq80DBFactory().open(primary.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(sharedNetherKey, new byte[]{3});
        }
        try (var db = new Iq80DBFactory().open(nether.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(separateNetherKey, new byte[]{7});
        }
        var destinations = Map.of(0, primary, 1, nether, 2, root.resolve("conflict-end"));
        var error = assertThrows(org.allaymc.api.world.storage.WorldStorageException.class,
                () -> DimensionLayoutTransition.ensureLayout(root, "conflict-world", "SEPARATE", destinations));
        assertTrue(error.getMessage().contains("conflict-world"));
        assertTrue(error.getMessage().contains("conflict-nether"));
        assertEquals(Set.of(1), DimensionStorageInspector.detectDimensionIds(primary));
        assertEquals(Set.of(1), DimensionStorageInspector.detectDimensionIds(nether));
        try (var db = new Iq80DBFactory().open(primary.resolve("db").toFile(),
                     new Options().createIfMissing(false));
             var other = new Iq80DBFactory().open(nether.resolve("db").toFile(),
                     new Options().createIfMissing(false))) {
            assertArrayEquals(new byte[]{3}, db.get(sharedNetherKey));
            assertArrayEquals(new byte[]{7}, other.get(separateNetherKey));
        }
    }

    @Test
    void retriesWithFreshStageWithoutOverwritingAbandonedCandidate() throws Exception {
        var overworld = root.resolve("recovery-world");
        var nether = root.resolve("recovery-world-nether");
        var end = root.resolve("recovery-world-end");
        Files.createDirectories(overworld.resolve("db"));
        try (var db = new Iq80DBFactory().open(overworld.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(new byte[]{0,0,0,0,0,0,0,0,44}, new byte[]{10});
            db.put(new byte[]{0,0,0,0,0,0,0,0,1,0,0,0,44}, new byte[]{11});
            db.put(new byte[]{0,0,0,0,0,0,0,0,2,0,0,0,44}, new byte[]{12});
        }
        var abandoned = root.resolve("recovery-world.layout-candidate");
        Files.createDirectories(abandoned);
        Files.writeString(abandoned.resolve("unfinished.txt"), "keep this data");

        assertTrue(DimensionLayoutTransition.ensureLayout(root, "recovery-world", "SEPARATE",
                Map.of(0, overworld, 1, nether, 2, end)));
        assertEquals("keep this data", Files.readString(abandoned.resolve("unfinished.txt")));
        assertEquals(Set.of(0), DimensionStorageInspector.detectDimensionIds(overworld));
        assertEquals(Set.of(1), DimensionStorageInspector.detectDimensionIds(nether));
        assertEquals(Set.of(2), DimensionStorageInspector.detectDimensionIds(end));
        assertTrue(Files.isDirectory(root.resolve(".dimension-layout-backups").resolve("recovery-world")));
    }


    @Test
    void separateUsesExistingCustomWorldsAndNeverSplitsLegacyWorld() throws Exception {
        var shared = root.resolve("custom-world");
        var nether = root.resolve("custom-nether");
        var end = root.resolve("custom-end");
        Files.createDirectories(shared.resolve("db"));
        Files.createDirectories(nether.resolve("db"));
        Files.createDirectories(end.resolve("db"));
        byte[] overworldKey = {0,0,0,0,0,0,0,0,44};
        byte[] sharedEndKey = {0,0,0,0,0,0,0,0,2,0,0,0,44};
        byte[] netherKey = {0,0,0,0,0,0,0,0,1,0,0,0,44};
        byte[] importedEndKey = {1,0,0,0,0,0,0,0,44};
        try (var db = new Iq80DBFactory().open(shared.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(overworldKey, new byte[]{1});
            db.put(sharedEndKey, new byte[]{2});
        }
        try (var db = new Iq80DBFactory().open(nether.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(netherKey, new byte[]{3});
        }
        try (var db = new Iq80DBFactory().open(end.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(importedEndKey, new byte[]{4});
        }
        var paths = Map.of(0, shared, 1, nether, 2, end);

        // SEPARATE reads the supplied dimension folders directly. Embedded
        // End records in the Overworld must neither trigger a split nor be
        // substituted for the imported custom End.
        assertFalse(DimensionLayoutTransition.ensureLayout(
                root, "custom-world", "SEPARATE", paths));
        assertEquals(Set.of(0, 2), DimensionStorageInspector.detectDimensionIds(shared));
        assertEquals(Set.of(1), DimensionStorageInspector.detectDimensionIds(nether));
        assertEquals(Set.of(0), DimensionStorageInspector.detectDimensionIds(end));
        assertFalse(Files.exists(root.resolve(".dimension-layout-backups")));
        try (var db = new Iq80DBFactory().open(shared.resolve("db").toFile(),
                new Options().createIfMissing(false))) {
            assertArrayEquals(new byte[]{1}, db.get(overworldKey));
            assertArrayEquals(new byte[]{2}, db.get(sharedEndKey));
        }

        // The existing custom End is converted *in its own folder* through
        // the backed-up one-time importer; no shared End keys are moved.
        assertTrue(DimensionStorageAutoConverter.ensureNativeLayout(
                root, "custom-world", end, 2, 0, 255));
        assertEquals(Set.of(2), DimensionStorageInspector.detectDimensionIds(end));
        assertFalse(DimensionLayoutTransition.ensureLayout(root, "custom-world", "SEPARATE", paths));
        assertEquals(Set.of(0, 2), DimensionStorageInspector.detectDimensionIds(shared));
        try (var db = new Iq80DBFactory().open(end.resolve("db").toFile(),
                new Options().createIfMissing(false))) {
            assertArrayEquals(new byte[]{4}, db.get(new byte[]{
                    1,0,0,0,0,0,0,0,2,0,0,0,44
            }));
        }
        assertTrue(Files.isDirectory(root.resolve(".dimension-backups/custom-world")));
    }

    @Test
    void refusesPartiallyConfiguredSeparateDimensions() throws Exception {
        var shared = root.resolve("one-custom-world");
        var end = root.resolve("one-custom-end");
        Files.createDirectories(shared.resolve("db"));
        Files.createDirectories(end.resolve("db"));
        try (var db = new Iq80DBFactory().open(shared.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(new byte[]{0,0,0,0,0,0,0,0,44}, new byte[]{1});
            db.put(new byte[]{0,0,0,0,0,0,0,0,2,0,0,0,44}, new byte[]{2});
        }
        try (var db = new Iq80DBFactory().open(end.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(new byte[]{1,0,0,0,0,0,0,0,44}, new byte[]{3});
        }
        assertThrows(org.allaymc.api.world.storage.WorldStorageException.class,
                () -> DimensionLayoutTransition.ensureLayout(root, "one-custom-world", "SEPARATE",
                        Map.of(0, shared, 1, root.resolve("missing-nether"), 2, end)));
        assertEquals(Set.of(0, 2), DimensionStorageInspector.detectDimensionIds(shared));
        assertEquals(Set.of(0), DimensionStorageInspector.detectDimensionIds(end));
    }


}
