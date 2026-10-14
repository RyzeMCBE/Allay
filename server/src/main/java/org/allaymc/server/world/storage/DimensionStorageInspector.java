package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/**
 * Read-only preflight for independently stored Bedrock dimensions.
 * The preflight never creates or rewrites databases. Imported overworld maps
 * must pass through the backed-up converter before they can be mounted as
 * Nether or End; silently treating them as empty loses visible terrain.
 */
public final class DimensionStorageInspector {
    private DimensionStorageInspector() {}

    public static void requireNativeLayout(Path dimensionFolder, int expectedDimensionId) {
        var seen = detectDimensionIds(dimensionFolder);
        return java.util.Set.copyOf(seen);
    }

    /**
     * Identify the physical source dimension without creating databases or
     * choosing a dimension arbitrarily when a folder contains mixed IDs.
     */
    public static java.util.Set<Integer> detectDimensionIds(Path dimensionFolder) {
        var database = dimensionFolder.resolve("db");
        if (!Files.isDirectory(database)) {
            // A configured path containing a map's level.dat but no database is
            // not an empty generated world and must not be silently overwritten.
            if (Files.exists(dimensionFolder.resolve("level.dat")) || Files.exists(database)) {
                throw new WorldStorageException("World folder has no readable LevelDB: " + dimensionFolder);
            }
            return java.util.Set.of();
        }

        var seen = new HashSet<Integer>();
        try (var db = new Iq80DBFactory().open(database.toFile(), new Options().createIfMissing(false));
             var iterator = db.iterator()) {
            for (iterator.seekToFirst(); iterator.hasNext();) {
                var key = iterator.next().getKey();
                var dimensionId = findDimensionId(key);
                if (dimensionId != null) {
                    seen.add(dimensionId);
                }
                // Mixed dimensions are unsafe in a separate folder. In particular,
                // source Overworld chunks cannot be read as target End chunks.
                if (seen.size() > 1) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new WorldStorageException(e);
        }

        if (!seen.isEmpty() && (seen.size() != 1 || !seen.contains(expectedDimensionId))) {
            throw new WorldStorageException(
                    "Dimension data in " + dimensionFolder + " uses storage IDs " + seen +
                    ", but this dimension requires " + expectedDimensionId +
                    ". A verified backup and one-time conversion are required before loading."
            );
        }
    }

    /**
     * Recognize Bedrock chunk keys: X/Z, optional dimension ID, record kind,
     * and optional chunk section Y. Never mistake actor and metadata keys for chunks.
     */
    static Integer findDimensionId(byte[] key) {
        if (key.length == 9 && isChunkRecord(key[8] & 0xff)) {
            return 0;
        }
        if (key.length == 10 && (key[8] & 0xff) == 47) {
            return 0;
        }
        if (key.length == 13 && isChunkRecord(key[12] & 0xff)) {
            return dimensionIdAt(key, 8);
        }
        if (key.length == 14 && (key[12] & 0xff) == 47) {
            return dimensionIdAt(key, 8);
        }
        return null;
    }

    private static int dimensionIdAt(byte[] key, int offset) {
        return (key[offset] & 0xff) |
               ((key[offset + 1] & 0xff) << 8) |
               ((key[offset + 2] & 0xff) << 16) |
               ((key[offset + 3] & 0xff) << 24);
    }

    private static boolean isChunkRecord(int type) {
        return switch (type) {
            case 43, 44, 45, 46, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57,
                 58, 59, 60, 61, 62, 63, 64, 65, 118, 119, 254, 255 -> true;
            default -> false;
        };
    }
}
