package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DimensionLevelDBConverterTest {
    @TempDir Path temporary;

    @Test
    void convertsChunkAndActorDigestPrefixesWithoutModifyingOriginal() throws Exception {
        var source = temporary.resolve("source");
        var stage = temporary.resolve("stage");
        Files.createDirectories(source.resolve("db"));
        var originalKey = new byte[]{1,0,0,0,2,0,0,0,44};
        var digest = new byte[]{'d','i','g','p',1,0,0,0,2,0,0,0};
        try (var db = new Iq80DBFactory().open(source.resolve("db").toFile(), new Options().createIfMissing(true))) {
            db.put(originalKey, new byte[]{42});
            db.put(digest, new byte[]{7,8});
        }
        assertEquals(2, DimensionLevelDBConverter.copyAsDimension(source, stage, 0, 2, -64, 319, 0, 255));
        try (var sourceDb = new Iq80DBFactory().open(source.resolve("db").toFile(), new Options().createIfMissing(false));
             var result = new Iq80DBFactory().open(stage.resolve("db").toFile(), new Options().createIfMissing(false))) {
            assertArrayEquals(new byte[]{42}, sourceDb.get(originalKey));
            assertArrayEquals(new byte[]{42}, result.get(new byte[]{1,0,0,0,2,0,0,0,2,0,0,0,44}));
            assertArrayEquals(new byte[]{7,8}, result.get(new byte[]{'d','i','g','p',1,0,0,0,2,0,0,0,2,0,0,0}));
        }
    }

    @Test
    void refusesOutOfHeightSections() {
        var section = new byte[]{0,0,0,0,0,0,0,0,47,(byte) -4};
        assertThrows(WorldStorageException.class,
                () -> DimensionLevelDBConverter.mapKey(section, 0, 2, 0, 255));
    }

    @Test
    void refusesMixedDimensions() {
        var netherRecord = new byte[]{0,0,0,0,0,0,0,0,1,0,0,0,44};
        assertThrows(WorldStorageException.class,
                () -> DimensionLevelDBConverter.mapKey(netherRecord, 0, 2, 0, 255));
    }
}
