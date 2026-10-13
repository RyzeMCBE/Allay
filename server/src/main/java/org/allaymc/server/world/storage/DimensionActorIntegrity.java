package org.allaymc.server.world.storage;

import org.allaymc.api.utils.AllayNBTUtils;
import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Read-only integrity check for Bedrock actor storage.
 *
 * <p>Actor digests (digp + chunk index) must refer to an existing
 * actorprefix + uniqueId record. Allay requires this correspondence to
 * deserialize entities for a chunk. Unrecognized actor key layouts or
 * malformed NBT fail conversion rather than silently losing entities.</p>
 */
public final class DimensionActorIntegrity {
    private static final byte[] DIGEST_PREFIX = "digp".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ACTOR_PREFIX = "actorprefix".getBytes(StandardCharsets.US_ASCII);

    private DimensionActorIntegrity() {}

    public static long verify(Path directory, int dimensionId) {
        var folder = directory.resolve("db");
        long referenced = 0;
        var seenIds = new HashSet<Long>();
        try (var db = new Iq80DBFactory().open(folder.toFile(), new Options().createIfMissing(false));
             var iterator = db.iterator()) {
            for (iterator.seekToFirst(); iterator.hasNext();) {
                var item = iterator.next();
                var key = item.getKey();
                if (startsWith(key, DIGEST_PREFIX)) {
                    int expectedLength = dimensionId == 0 ? 12 : 16;
                    if (key.length != expectedLength) {
                        throw new WorldStorageException("Unsupported actor digest key length " + key.length);
                    }
                    if (dimensionId != 0 && readIntLE(key, 12) != dimensionId) {
                        throw new WorldStorageException("Actor digest belongs to a different dimension");
                    }
                    var digest = item.getValue();
                    if (digest.length % Long.BYTES != 0) {
                        throw new WorldStorageException("Actor digest contains incomplete unique IDs");
                    }
                    for (int offset = 0; offset < digest.length; offset += Long.BYTES) {
                        var id = readLongLE(digest, offset);
                        if (!seenIds.add(id)) {
                            throw new WorldStorageException("Actor ID referenced by multiple chunk digests: " + id);
                        }
                        var actorKey = new byte[ACTOR_PREFIX.length + Long.BYTES];
                        System.arraycopy(ACTOR_PREFIX, 0, actorKey, 0, ACTOR_PREFIX.length);
                        System.arraycopy(digest, offset, actorKey, ACTOR_PREFIX.length, Long.BYTES);
                        var actorNbt = db.get(actorKey);
                        if (actorNbt == null || actorNbt.length == 0) {
                            throw new WorldStorageException("Missing actor NBT for actor " + id);
                        }
                        try {
                            if (AllayNBTUtils.bytesToNbtLE(actorNbt) == null) {
                                throw new WorldStorageException("Invalid actor NBT for actor " + id);
                            }
                        } catch (RuntimeException e) {
                            throw new WorldStorageException("Malformed actor NBT for actor " + id, e);
                        }
                        referenced++;
                    }
                } else if (startsWith(key, ACTOR_PREFIX) && key.length != ACTOR_PREFIX.length + Long.BYTES) {
                    throw new WorldStorageException("Unsupported actor index key length " + key.length);
                }
            }
        } catch (IOException e) {
            throw new WorldStorageException("Cannot inspect dimension actor records at " + folder, e);
        }
        return referenced;
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8)
                | ((data[offset + 2] & 255) << 16) | ((data[offset + 3] & 255) << 24);
    }

    private static long readLongLE(byte[] data, int offset) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result |= (data[offset + i] & 255L) << (i * 8);
        }
        return result;
    }
}
