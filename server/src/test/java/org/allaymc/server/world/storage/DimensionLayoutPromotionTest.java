package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DimensionLayoutPromotionTest {
    @TempDir Path root;

    private Path world(String name, int dim) throws Exception {
        var folder = root.resolve(name);
        Files.createDirectories(folder.resolve("db"));
        try (var db = new Iq80DBFactory().open(folder.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            var key = dim == 0
                    ? new byte[]{1,0,0,0,2,0,0,0,44}
                    : new byte[]{1,0,0,0,2,0,0,0,(byte) dim,0,0,0,44};
            db.put(key, new byte[]{(byte) dim});
        }
        return folder;
    }

    @Test
    void commitsSeveralWorldFoldersWhileRetainingPreviousVersions() throws Exception {
        var overworld = world("world", 0);
        var nether = world("nether", 1);
        var nextOverworld = world("world-stage", 0);
        var nextNether = world("nether-stage", 1);
        Files.writeString(nextOverworld.resolve("new-version.txt"), "candidate");
        var journal = root.resolve(".layout-journal").resolve("test.pending");

        DimensionLayoutPromotion.promote(root, journal, List.of(
                new DimensionLayoutPromotion.Change(overworld, nextOverworld),
                new DimensionLayoutPromotion.Change(nether, nextNether)));

        assertEquals("candidate", Files.readString(overworld.resolve("new-version.txt")));
        assertFalse(Files.exists(nextOverworld));
        assertFalse(Files.exists(nextNether));
        assertFalse(Files.exists(journal));
        try (var paths = Files.list(root)) {
            assertEquals(2, paths.filter(p -> p.getFileName().toString().contains(".layout-previous-")).count());
        }
    }

    @Test
    void supportsArchivingObsoleteSeparateDimensionWithoutDeletingIt() throws Exception {
        var oldNether = world("old-nether", 1);
        var journal = root.resolve("nether.pending");
        DimensionLayoutPromotion.promote(root, journal,
                List.of(new DimensionLayoutPromotion.Change(oldNether, null)));
        assertFalse(Files.exists(oldNether));
        try (var paths = Files.list(root)) {
            assertEquals(1, paths.filter(p -> p.getFileName().toString().startsWith("old-nether.layout-previous-")).count());
        }
    }

    @Test
    void rejectsMissingStageBeforeAnyMove() throws Exception {
        var active = world("current", 0);
        var journal = root.resolve("journal");
        assertThrows(WorldStorageException.class, () -> DimensionLayoutPromotion.promote(
                root, journal, List.of(new DimensionLayoutPromotion.Change(active, root.resolve("missing-stage")))));
        assertTrue(Files.isDirectory(active.resolve("db")));
        assertFalse(Files.exists(journal));
    }
}
