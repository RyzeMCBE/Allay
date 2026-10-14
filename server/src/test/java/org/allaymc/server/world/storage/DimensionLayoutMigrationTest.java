package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DimensionLayoutMigrationTest {
    @TempDir Path root;

    @Test
    void splitAndMergePreservesAllThreeDimensionsAndGlobalData() throws Exception {
        var shared = root.resolve("shared");
        Files.createDirectories(shared.resolve("db"));
        Files.writeString(shared.resolve("level.dat"), "Spawn 7,64,9");
        var overworld = new byte[]{2,0,0,0,3,0,0,0,44};
        var nether = new byte[]{2,0,0,0,3,0,0,0,1,0,0,0,44};
        var end = new byte[]{2,0,0,0,3,0,0,0,2,0,0,0,44};
        try (var db = new Iq80DBFactory().open(shared.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(overworld, new byte[]{10});
            db.put(nether, new byte[]{11});
            db.put(end, new byte[]{12});
            db.put(new byte[]{'w','o','r','l','d'}, new byte[]{9});
        }

        var destinations = Map.of(
                0, root.resolve("overworld"),
                1, root.resolve("nether"),
                2, root.resolve("end"));
        var counts = DimensionLayoutMigration.splitToCandidates(shared, destinations);
        assertEquals(2L, counts.get(0));
        assertEquals(1L, counts.get(1));
        assertEquals(1L, counts.get(2));
        assertEquals("Spawn 7,64,9", Files.readString(destinations.get(0).resolve("level.dat")));
        assertFalse(Files.exists(destinations.get(2).resolve("level.dat")));

        var merged = root.resolve("merged");
        assertEquals(4L, DimensionLayoutMigration.mergeToCandidate(destinations, merged));
        try (var db = new Iq80DBFactory().open(merged.resolve("db").toFile(),
                new Options().createIfMissing(false))) {
            assertArrayEquals(new byte[]{10}, db.get(overworld));
            assertArrayEquals(new byte[]{11}, db.get(nether));
            assertArrayEquals(new byte[]{12}, db.get(end));
            assertArrayEquals(new byte[]{9}, db.get(new byte[]{'w','o','r','l','d'}));
        }
        assertEquals("Spawn 7,64,9", Files.readString(merged.resolve("level.dat")));
    }

    @Test
    void refusesDestinationAlreadyInUse() throws Exception {
        var shared = root.resolve("source");
        Files.createDirectories(shared.resolve("db"));
        try (var db = new Iq80DBFactory().open(shared.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(new byte[]{0,0,0,0,0,0,0,0,44}, new byte[]{1});
        }
        var conflict = root.resolve("existing");
        Files.createDirectories(conflict);
        assertThrows(WorldStorageException.class, () -> DimensionLayoutMigration.splitToCandidates(
                shared, Map.of(0, conflict, 1, root.resolve("nether"), 2, root.resolve("end"))));
        assertTrue(Files.isDirectory(conflict));
    }
    @Test
    void keepsCustomEndLevelDatWhenMergingAndSplittingAgain() throws Exception {
        var inputs = Map.of(0, root.resolve("base-world"),
                1, root.resolve("base-nether"), 2, root.resolve("base-end"));
        for (int id = 0; id <= 2; id++) {
            var folder = inputs.get(id);
            Files.createDirectories(folder.resolve("db"));
            try (var db = new Iq80DBFactory().open(folder.resolve("db").toFile(),
                    new Options().createIfMissing(true))) {
                var key = id == 0
                        ? new byte[]{0,0,0,0,0,0,0,0,44}
                        : new byte[]{0,0,0,0,0,0,0,0,(byte) id,0,0,0,44};
                db.put(key, new byte[]{(byte) id});
            }
        }
        Files.writeString(inputs.get(0).resolve("level.dat"), "Overworld spawn");
        Files.writeString(inputs.get(2).resolve("level.dat"), "Imported custom End spawn");
        Files.writeString(inputs.get(1).resolve("level.dat"), "Imported custom Nether spawn");

        var merged = root.resolve("merged-world");
        assertEquals(3L, DimensionLayoutMigration.mergeToCandidate(inputs, merged));
        assertEquals("Imported custom End spawn", Files.readString(
                DimensionSpawnMetadata.sidecarFolder(merged, 2).resolve("level.dat")));
        assertEquals("Imported custom Nether spawn", Files.readString(
                DimensionSpawnMetadata.sidecarFolder(merged, 1).resolve("level.dat")));

        var back = Map.of(0, root.resolve("roundtrip-world"),
                1, root.resolve("roundtrip-nether"), 2, root.resolve("roundtrip-end"));
        DimensionLayoutMigration.splitToCandidates(merged, back);
        assertEquals("Imported custom End spawn", Files.readString(back.get(2).resolve("level.dat")));
        assertEquals("Imported custom Nether spawn", Files.readString(back.get(1).resolve("level.dat")));
    }

    @Test
    void reportsUnknownChunkKeyWithoutTouchingOriginalDatabase() throws Exception {
        var shared = root.resolve("unknown-key-shared");
        Files.createDirectories(shared.resolve("db"));
        var unknownKey = new byte[]{1,0,0,0,2,0,0,0,99};
        try (var db = new Iq80DBFactory().open(shared.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(unknownKey, new byte[]{42});
        }

        var outputs = Map.of(0, root.resolve("out0"),
                1, root.resolve("out1"), 2, root.resolve("out2"));
        var failure = assertThrows(WorldStorageException.class,
                () -> DimensionLayoutMigration.splitToCandidates(shared, outputs));
        assertTrue(failure.getMessage().contains("010000000200000063"));
        assertTrue(failure.getMessage().contains("keyLength=9"));
        assertFalse(Files.exists(outputs.get(0)));
        assertFalse(Files.exists(outputs.get(1)));
        assertFalse(Files.exists(outputs.get(2)));
        try (var source = new Iq80DBFactory().open(shared.resolve("db").toFile(),
                new Options().createIfMissing(false))) {
            assertArrayEquals(new byte[]{42}, source.get(unknownKey));
        }
    }

}
