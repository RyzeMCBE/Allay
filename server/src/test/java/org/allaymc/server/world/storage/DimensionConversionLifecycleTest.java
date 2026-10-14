package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-level, on-disk conversion tests with real LevelDB folders.
 * Does not depend on an active server or mounted production world.
 */
class DimensionConversionLifecycleTest {
    @TempDir Path workspace;

    private static final byte[] SOURCE_CHUNK = {7,0,0,0,9,0,0,0,44};
    private static final byte[] END_CHUNK = {7,0,0,0,9,0,0,0,2,0,0,0,44};

    private Path createOriginal() throws Exception {
        var world = workspace.resolve("custom-end");
        Files.createDirectories(world.resolve("db"));
        Files.writeString(world.resolve("level.dat"), "Original spawn metadata");
        try (var db = new Iq80DBFactory().open(world.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(SOURCE_CHUNK, new byte[]{42});
        }
        return world;
    }

    @Test
    void verifiedBackupIsRetainedAfterSuccessfulPromotion() throws Exception {
        var original = createOriginal();
        var backup = workspace.resolve("saved-custom-end");
        var candidate = workspace.resolve("candidate-custom-end");

        assertEquals(1, DimensionConversionPreparation.prepare(
                original, backup, candidate, 0, 2, -64, 319, 0, 255));
        DimensionConversionManifest.verify(candidate, 2);
        DimensionConversionPromotion.promote(original, backup, candidate, 2);

        assertTrue(Files.exists(original.resolve(DimensionConversionManifest.FILE_NAME)));
        DimensionConversionManifest.verify(original, 2);
        assertEquals("Original spawn metadata", Files.readString(original.resolve("level.dat")));
        assertEquals("Original spawn metadata", Files.readString(backup.resolve("level.dat")));
        try (var active = new Iq80DBFactory().open(original.resolve("db").toFile(),
                new Options().createIfMissing(false));
             var saved = new Iq80DBFactory().open(backup.resolve("db").toFile(),
                     new Options().createIfMissing(false))) {
            assertArrayEquals(new byte[]{42}, active.get(END_CHUNK));
            assertNull(active.get(SOURCE_CHUNK));
            assertArrayEquals(new byte[]{42}, saved.get(SOURCE_CHUNK));
        }
        assertTrue(Files.isDirectory(DimensionConversionPromotion.previousPath(original)));
        assertFalse(Files.exists(DimensionConversionPromotion.journalPath(original)));
    }

    @Test
    void refusesPromotionIfOriginalHasChangedAfterBackup() throws Exception {
        var original = createOriginal();
        var backup = workspace.resolve("backup");
        var candidate = workspace.resolve("staging");
        DimensionConversionPreparation.prepare(original, backup, candidate, 0, 2, -64, 319, 0, 255);
        Files.writeString(original.resolve("level.dat"), "World was changed while offline conversion ran");
        assertThrows(WorldStorageException.class,
                () -> DimensionConversionPromotion.promote(original, backup, candidate, 2));
        assertTrue(Files.isDirectory(original.resolve("db")));
        assertFalse(Files.exists(DimensionConversionPromotion.previousPath(original)));
        assertFalse(Files.exists(DimensionConversionPromotion.journalPath(original)));
        assertEquals("Original spawn metadata", Files.readString(backup.resolve("level.dat")));
        assertTrue(Files.isDirectory(candidate.resolve("db")));
    }

    @Test
    void refusesPromotionIfCandidateMetadataWasModified() throws Exception {
        var original = createOriginal();
        var backup = workspace.resolve("backup");
        var candidate = workspace.resolve("staging");
        DimensionConversionPreparation.prepare(original, backup, candidate, 0, 2, -64, 319, 0, 255);
        Files.writeString(candidate.resolve("level.dat"), "Tampered candidate");
        assertThrows(WorldStorageException.class,
                () -> DimensionConversionPromotion.promote(original, backup, candidate, 2));
        assertFalse(Files.exists(DimensionConversionPromotion.previousPath(original)));
        assertFalse(Files.exists(DimensionConversionPromotion.journalPath(original)));
        assertEquals("Original spawn metadata", Files.readString(original.resolve("level.dat")));
    }
}
