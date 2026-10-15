package org.allaymc.server.world.storage;

import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DimensionConversionPreparationTest {
    @TempDir Path root;

    @Test
    void preparesSeparateBackupAndCandidateWithoutChangingSource() throws Exception {
        var world = root.resolve("end");
        var backup = root.resolve("backups").resolve("end-original");
        var stage = root.resolve("staging").resolve("end-converted");
        Files.createDirectories(world.resolve("db"));
        var key = new byte[]{1,0,0,0,2,0,0,0,44};
        Files.writeString(world.resolve("levelname.txt"), "Custom End Map");
        try (var db = new Iq80DBFactory().open(world.resolve("db").toFile(), new Options().createIfMissing(true))) {
            db.put(key, new byte[]{88});
        }
        var count = DimensionConversionPreparation.prepare(world, backup, stage, 0, 2, -64, 319, 0, 255);
        assertEquals(1, count);
        assertEquals("Custom End Map", Files.readString(backup.resolve("levelname.txt")));
        assertEquals("Custom End Map", Files.readString(stage.resolve("levelname.txt")));
        try (var original = new Iq80DBFactory().open(world.resolve("db").toFile(), new Options().createIfMissing(false));
             var staged = new Iq80DBFactory().open(stage.resolve("db").toFile(), new Options().createIfMissing(false))) {
            assertArrayEquals(new byte[]{88}, original.get(key));
            assertArrayEquals(new byte[]{88}, staged.get(new byte[]{1,0,0,0,2,0,0,0,2,0,0,0,44}));
        }
    }

    @Test
    void neverOverwritesExistingBackup() throws Exception {
        var world = root.resolve("original");
        var backup = root.resolve("saved");
        var stage = root.resolve("converted");
        Files.createDirectories(world.resolve("db"));
        Files.createDirectories(backup);
        assertThrows(RuntimeException.class, () ->
                DimensionConversionPreparation.prepare(world, backup, stage, 0, 2, -64, 319, 0, 255));
    }

    @Test
    void detectsChangedOrUnexpectedBackupFiles() throws Exception {
        var original = root.resolve("unchanged");
        var backup = root.resolve("saved-copy");
        Files.createDirectories(original);
        Files.createDirectories(backup);
        Files.writeString(original.resolve("levelname.txt"), "original");
        Files.writeString(backup.resolve("levelname.txt"), "original");
        DimensionConversionPreparation.verifyBackup(original, backup);
        Files.writeString(backup.resolve("levelname.txt"), "modified");
        assertThrows(RuntimeException.class,
                () -> DimensionConversionPreparation.verifyBackup(original, backup));
        Files.writeString(backup.resolve("levelname.txt"), "original");
        Files.writeString(backup.resolve("unexpected.txt"), "unexpected");
        assertThrows(RuntimeException.class,
                () -> DimensionConversionPreparation.verifyBackup(original, backup));
    }
}
