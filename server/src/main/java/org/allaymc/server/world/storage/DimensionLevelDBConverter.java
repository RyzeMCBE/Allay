package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Offline, non-destructive copier for a single-dimension Bedrock LevelDB.
 * The source database is NEVER modified. All target records are written into
 * a previously absent staging directory, then verified by the caller.
 */
public final class DimensionLevelDBConverter {
    private DimensionLevelDBConverter() {}

    public static long copyAsDimension(Path source, Path target, int sourceId, int targetId,
                                       int sourceMinY, int sourceMaxY, int targetMinY, int targetMaxY) {
        if (sourceId != 0 || (targetId != 1 && targetId != 2)) {
            throw new IllegalArgumentException("Only imported Overworld (0) to Nether (1) or End (2) is supported");
        }
        if (!Files.isDirectory(source.resolve("db")) || Files.exists(target)) {
            throw new WorldStorageException("Expected an existing source DB and a fresh staging directory");
        }
        try {
            Files.createDirectories(target.resolve("db"));
            var readOptions = new Options().createIfMissing(false);
            var writeOptions = new Options().createIfMissing(false);
            long count = 0;
            long changed = 0;
            try (var input = new Iq80DBFactory().open(source.resolve("db").toFile(), readOptions);
                 var output = new Iq80DBFactory().open(target.resolve("db").toFile(), writeOptions);
                 var iterator = input.iterator()) {
                iterator.seekToFirst();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    var key = entry.getKey();
                    var value = entry.getValue();
                    var mappedKey = mapKey(key, sourceId, targetId, targetMinY, targetMaxY);
                    var mappedValue = value;
                    if (isData3DKey(key)) {
                        mappedValue = DimensionData3DConverter.remap(
                                value, sourceMinY, sourceMaxY, targetMinY, targetMaxY
                        );
                    }
                    if (output.get(mappedKey) != null) {
                        throw new WorldStorageException("Duplicate LevelDB key while converting chunk records");
                    }
                    output.put(mappedKey, mappedValue);
                    count++;
                    if (!Arrays.equals(key, mappedKey)) changed++;
                }
            }
            if (count == 0 || changed == 0) {
                throw new WorldStorageException("No convertible chunk records were found");
            }
            // Compare record counts after reopening both DBs, not just successful writes.
            if (countKeys(source.resolve("db")) != count || countKeys(target.resolve("db")) != count) {
                throw new WorldStorageException("LevelDB record count verification failed");
            }
            DimensionStorageInspector.requireNativeLayout(target, targetId);
            return count;
        } catch (IOException e) {
            throw new WorldStorageException("Could not create or read conversion staging database", e);
        }
    }

    private static long countKeys(Path path) throws IOException {
        long count = 0;
        try (var db = new Iq80DBFactory().open(path.toFile(), new Options().createIfMissing(false));
             var iterator = db.iterator()) {
            for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) count++;
        }
        return count;
    }

    static boolean isData3DKey(byte[] key) {
        return (key.length == 9 && (key[8] & 0xff) == 43) ||
               (key.length == 13 && (key[12] & 0xff) == 43);
    }

    static byte[] mapKey(byte[] key, int sourceId, int targetId, int targetMinY, int targetMaxY) {
        if (key.length == 12 && hasDigestPrefix(key)) {
            if (sourceId != 0) throw new WorldStorageException("Unexpected actor digest source dimension");
            return appendDimension(key, targetId);
        }
        var dimension = DimensionStorageInspector.findDimensionId(key);
        if (dimension == null) return key.clone();
        if (dimension != sourceId) {
            throw new WorldStorageException("Mixed dimension records in source database: " + dimension);
        }
        if (isSectionKey(key)) {
            int sectionY = key[key.length - 1]; // signed, including negative Overworld sections
            if ((sectionY << 4) < targetMinY || (sectionY << 4) + 15 > targetMaxY) {
                throw new WorldStorageException("Chunk section Y=" + sectionY + " lies outside target dimension bounds");
            }
        }
        if (sourceId != 0) throw new WorldStorageException("Unsupported source dimension");
        var result = new byte[key.length + 4];
        System.arraycopy(key, 0, result, 0, 8);
        writeIntLE(result, 8, targetId);
        System.arraycopy(key, 8, result, 12, key.length - 8);
        return result;
    }

    private static boolean hasDigestPrefix(byte[] key) {
        return key[0] == 'd' && key[1] == 'i' && key[2] == 'g' && key[3] == 'p';
    }

    private static boolean isSectionKey(byte[] key) {
        return (key.length == 10 && (key[8] & 0xff) == 47) ||
               (key.length == 14 && (key[12] & 0xff) == 47);
    }

    private static byte[] appendDimension(byte[] key, int targetId) {
        var result = Arrays.copyOf(key, key.length + 4);
        writeIntLE(result, key.length, targetId);
        return result;
    }

    private static void writeIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
