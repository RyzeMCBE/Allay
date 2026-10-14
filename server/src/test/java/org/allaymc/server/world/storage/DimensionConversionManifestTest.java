package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DimensionConversionManifestTest {
    @TempDir Path folder;

    @Test
    void verifiesSealedRecordsAndRejectsChangedContent() throws Exception {
        var candidate = folder.resolve("end");
        Files.createDirectories(candidate.resolve("db"));
        var key = new byte[]{0,0,0,0,0,0,0,0,2,0,0,0,44};
        try (var db = new Iq80DBFactory().open(candidate.resolve("db").toFile(), new Options().createIfMissing(true))) {
            db.put(key, new byte[]{42});
        }
        Files.writeString(candidate.resolve("level.dat"), "Original custom spawn metadata");
        DimensionConversionManifest.seal(candidate, 2, 1);
        DimensionConversionManifest.verify(candidate, 2);
        Files.writeString(candidate.resolve("level.dat"), "Modified custom spawn metadata");
        assertThrows(WorldStorageException.class, () -> DimensionConversionManifest.verify(candidate, 2));
        Files.writeString(candidate.resolve("level.dat"), "Original custom spawn metadata");
        DimensionConversionManifest.verify(candidate, 2);
        Files.writeString(candidate.resolve("additional-file.txt"), "Unexpected file");
        assertThrows(WorldStorageException.class, () -> DimensionConversionManifest.verify(candidate, 2));
        Files.delete(candidate.resolve("additional-file.txt"));
        DimensionConversionManifest.verify(candidate, 2);
        assertThrows(WorldStorageException.class, () -> DimensionConversionManifest.verify(candidate, 1));
        try (var db = new Iq80DBFactory().open(candidate.resolve("db").toFile(), new Options().createIfMissing(false))) {
            db.put(key, new byte[]{43});
        }
        assertThrows(WorldStorageException.class, () -> DimensionConversionManifest.verify(candidate, 2));
    }

    @Test
    void failsWhenManifestMissingOrIncorrectCount() throws Exception {
        var candidate = folder.resolve("nether");
        Files.createDirectories(candidate.resolve("db"));
        try (var db = new Iq80DBFactory().open(candidate.resolve("db").toFile(), new Options().createIfMissing(true))) {
            db.put(new byte[]{0,0,0,0,0,0,0,0,1,0,0,0,44}, new byte[]{1});
        }
        assertThrows(WorldStorageException.class, () -> DimensionConversionManifest.verify(candidate, 1));
        assertThrows(WorldStorageException.class, () -> DimensionConversionManifest.seal(candidate, 1, 2));
        assertFalse(Files.exists(candidate.resolve(DimensionConversionManifest.FILE_NAME)));
    }
}
