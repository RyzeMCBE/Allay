package org.allaymc.server.world.storage;

import org.allaymc.api.utils.AllayNBTUtils;
import org.allaymc.api.world.storage.WorldStorageException;
import org.cloudburstmc.nbt.NbtMap;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DimensionActorIntegrityTest {
    @TempDir Path temp;

    @Test
    void rejectsMissingActorRecord() throws Exception {
        var source = temp.resolve("missing");
        Files.createDirectories(source.resolve("db"));
        try (var db = new Iq80DBFactory().open(source.resolve("db").toFile(), new Options().createIfMissing(true))) {
            db.put(new byte[]{'d','i','g','p',0,0,0,0,0,0,0,0}, new byte[]{1,0,0,0,0,0,0,0});
        }
        assertThrows(WorldStorageException.class, () -> DimensionActorIntegrity.verify(source, 0));
    }

    @Test
    void acceptsValidActorDigestAndNbt() throws Exception {
        var source = temp.resolve("valid");
        Files.createDirectories(source.resolve("db"));
        var id = new byte[]{1,0,0,0,0,0,0,0};
        var actorKey = new byte[]{'a','c','t','o','r','p','r','e','f','i','x',1,0,0,0,0,0,0,0};
        try (var db = new Iq80DBFactory().open(source.resolve("db").toFile(), new Options().createIfMissing(true))) {
            db.put(new byte[]{'d','i','g','p',0,0,0,0,0,0,0,0}, id);
            db.put(actorKey, AllayNBTUtils.nbtToBytesLE(NbtMap.builder().putString("identifier", "minecraft:pig").build()));
        }
        assertEquals(1, DimensionActorIntegrity.verify(source, 0));
    }

    @Test
    void rejectsMalformedActorDigest() throws Exception {
        var source = temp.resolve("bad-digest");
        Files.createDirectories(source.resolve("db"));
        try (var db = new Iq80DBFactory().open(source.resolve("db").toFile(), new Options().createIfMissing(true))) {
            db.put(new byte[]{'d','i','g','p',0,0,0,0,0,0,0,0}, new byte[]{1});
        }
        assertThrows(WorldStorageException.class, () -> DimensionActorIntegrity.verify(source, 0));
    }
}
