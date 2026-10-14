package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DimensionStorageAutoConverterTest {
    @TempDir Path temp;

    private Path createCustomWorld(Path root) throws Exception {
        var source = root.resolve("custom-end");
        Files.createDirectories(source.resolve("db"));
        Files.writeString(source.resolve("level.dat"), "Imported End spawn");
        try (var db = new Iq80DBFactory().open(source.resolve("db").toFile(),
                new Options().createIfMissing(true))) {
            db.put(new byte[]{3,0,0,0,5,0,0,0,44}, new byte[]{7});
        }
        return source;
    }

    @Test
    void convertsOnceAndKeepsPermanentBackupAndRetiredOriginal() throws Exception {
        var root = temp.resolve("worlds");
        var source = createCustomWorld(root);
        assertTrue(DimensionStorageAutoConverter.ensureNativeLayout(root, "world", source, 2, 0, 255));
        assertFalse(DimensionStorageAutoConverter.ensureNativeLayout(root, "world", source, 2, 0, 255));
        assertEquals(java.util.Set.of(2), DimensionStorageInspector.detectDimensionIds(source));
        var backupRoot = root.resolve(".dimension-backups").resolve("world");
        try (var paths = Files.list(backupRoot)) {
            var saved = paths.toList();
            assertEquals(1, saved.size());
            assertEquals(java.util.Set.of(0), DimensionStorageInspector.detectDimensionIds(saved.getFirst()));
        }
        assertTrue(Files.isDirectory(DimensionConversionPromotion.previousPath(source)));
        assertFalse(Files.exists(DimensionConversionPromotion.journalPath(source)));
        assertEquals("Imported End spawn", Files.readString(source.resolve("level.dat")));
    }

    @Test
    void rejectsExistingCandidateRatherThanOverwritingIt() throws Exception {
        var root = temp.resolve("collision");
        var source = createCustomWorld(root);
        var candidate = source.resolveSibling(source.getFileName() + ".allay-conversion-candidate");
        Files.createDirectories(candidate);
        assertThrows(WorldStorageException.class,
                () -> DimensionStorageAutoConverter.ensureNativeLayout(root, "world", source, 2, 0, 255));
        assertEquals(java.util.Set.of(0), DimensionStorageInspector.detectDimensionIds(source));
        assertTrue(Files.exists(candidate));
    }
}
