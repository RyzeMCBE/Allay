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
}
