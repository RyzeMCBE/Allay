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
        // Reject unknown or ambiguous chunk key layouts before creating staging data.
        // This conservative guard is particularly important for mixed or newer Bedrock worlds.
        requireKnownChunkLayouts(source);
        DimensionActorIntegrity.verify(source, sourceId);
        try {
            Files.createDirectories(target.resolve("db"));
            var readOptions = new Options().createIfMissing(false);
            var writeOptions = new Options().createIfMissing(true);
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
            verifyConvertedRecords(source.resolve("db"), target.resolve("db"),
                    sourceId, targetId, sourceMinY, sourceMaxY, targetMinY, targetMaxY, count);
            DimensionStorageInspector.requireNativeLayout(target, targetId);
            DimensionActorIntegrity.verify(target, targetId);
            return count;
        } catch (IOException e) {
            throw new WorldStorageException("Could not create or read conversion staging database", e);
        }
    }

    private static void requireKnownChunkLayouts(Path source) {
        try (var db = new Iq80DBFactory().open(source.resolve("db").toFile(), new Options().createIfMissing(false));
             var iterator = db.iterator()) {
            for (iterator.seekToFirst(); iterator.hasNext();) {
                var key = iterator.next().getKey();
                if (key.length == 0) throw new WorldStorageException("Empty LevelDB key");
                // Chunk record identifiers are located after X/Z and optional dimension ID.
                // For unknown record types with a plausible chunk prefix, reject rather
                // // than preserve them under the wrong dimension.
                if ((key.length == 9 || key.length == 10 || key.length == 13 || key.length == 14)
                        && !isKnownChunkShape(key)) {
                    throw new WorldStorageException("Unrecognized chunk-shaped LevelDB key of length " + key.length);
                }
            }
        } catch (IOException e) {
            throw new WorldStorageException("Cannot inspect source LevelDB chunk keys", e);
        }
    }

    private static boolean isKnownChunkShape(byte[] key) {
        if (DimensionStorageInspector.findDimensionId(key) != null) return true;
        return key.length >= 4 && (key[0] == 'd' && key[1] == 'i' && key[2] == 'g' && key[3] == 'p');
    }

    private static void verifyConvertedRecords(Path sourceDb, Path targetDb,
                                               int sourceId, int targetId,
                                               int sourceMinY, int sourceMaxY,
                                               int targetMinY, int targetMaxY,
                                               long expectedCount) throws IOException {
        long matched = 0;
        try (var source = new Iq80DBFactory().open(sourceDb.toFile(), new Options().createIfMissing(false));
             var destination = new Iq80DBFactory().open(targetDb.toFile(), new Options().createIfMissing(false));
             var iterator = source.iterator();
             var destinationIterator = destination.iterator()) {
            iterator.seekToFirst();
            destinationIterator.seekToFirst();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var targetKey = mapKey(entry.getKey(), sourceId, targetId, targetMinY, targetMaxY);
                var expectedValue = isData3DKey(entry.getKey())
                        ? DimensionData3DConverter.remap(entry.getValue(), sourceMinY, sourceMaxY, targetMinY, targetMaxY)
                        : entry.getValue();
                if (!Arrays.equals(expectedValue, destination.get(targetKey))) {
                    throw new WorldStorageException("Converted LevelDB record differs from its source");
                }
                matched++;
            }
            long actual = 0;
            while (destinationIterator.hasNext()) {
                destinationIterator.next();
                actual++;
            }
            if (matched != expectedCount || actual != expectedCount) {
                throw new WorldStorageException("LevelDB record count verification failed");
            }
        }
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
        if (dimension == null) {
            if (hasDigestPrefixSafe(key)) {
                throw new WorldStorageException("Unsupported actor digest key format");
            }
            return key.clone();
        }
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

    private static boolean hasDigestPrefixSafe(byte[] key) {
        return key.length >= 4 && hasDigestPrefix(key);
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
