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
    @Test
    void recoversOriginalAfterSimulatedCrashMidPromotion() throws Exception {
        var target = world("crash-world", 0);
        Files.writeString(target.resolve("original.txt"), "safe");
        var previous = root.resolve("crash-world.layout-previous-crash");
        var failed = root.resolve("crash-world.layout-failed-crash");
        var candidate = world("crash-stage", 0);
        Files.writeString(candidate.resolve("candidate.txt"), "new");
        var journal = root.resolve("crash.pending");

        var content = "allay-dimension-layout-v1\n" + encode(root) + "\n" +
                encode(target) + "|" + encode(candidate) + "|" + encode(previous) + "|" +
                encode(failed) + "|1|-\n";
        Files.writeString(journal, content);
        Files.move(target, previous, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Files.move(candidate, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        assertTrue(DimensionLayoutPromotion.recover(root, journal));
        assertEquals("safe", Files.readString(target.resolve("original.txt")));
        assertEquals("new", Files.readString(failed.resolve("candidate.txt")));
        assertFalse(Files.exists(journal));
        assertFalse(DimensionLayoutPromotion.recover(root, journal));
    }

    private static String encode(Path path) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                path.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

}
