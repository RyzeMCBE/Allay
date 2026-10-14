package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Offline staging of SHARED <-> SEPARATE LevelDB layouts. Never mutates source
 * databases or replaces running worlds. The caller must supply closed,
 * verified source copies and fresh, disjoint candidate paths, then use a
 * separately journaled promotion to install a complete layout.
 *
 * <p>Chunk keys retain their native dimension IDs: the migration only routes
 * physical records. Global actor records are assigned using the corresponding
 * dimension-scoped digp index. Ambiguous actor references fail closed.</p>
 */
public final class DimensionLayoutMigration {
    private static final byte[] ACTOR = "actorprefix".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] DIGEST = "digp".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private DimensionLayoutMigration() {}

    public static Map<Integer, Long> splitToCandidates(Path sharedInput, Map<Integer, Path> candidates) {
        if (!candidates.keySet().equals(java.util.Set.of(0, 1, 2))) {
            throw new IllegalArgumentException("Provide independent candidates for Overworld, Nether and End");
        }
        validatePaths(java.util.List.of(sharedInput), candidates.values());
        try {
            var ownership = collectActorOwners(sharedInput.resolve("db"));
            var counts = new HashMap<Integer, Long>();
            var output = new HashMap<Integer, DB>();
            try {
                for (int id = 0; id <= 2; id++) {
                    var folder = candidates.get(id);
                    Files.createDirectories(folder.resolve("db"));
                    output.put(id, new Iq80DBFactory().open(folder.resolve("db").toFile(),
                            new Options().createIfMissing(true)));
                    counts.put(id, 0L);
                }
                try (var db = new Iq80DBFactory().open(sharedInput.resolve("db").toFile(),
                        new Options().createIfMissing(false)); var iterator = db.iterator()) {
                    for (iterator.seekToFirst(); iterator.hasNext();) {
                        var entry = iterator.next();
                        int id = classify(entry.getKey(), ownership, true);
                        var out = output.get(id);
                        if (out.get(entry.getKey()) != null) throw new WorldStorageException("Duplicate staged key");
                        out.put(entry.getKey(), entry.getValue());
                        counts.merge(id, 1L, Long::sum);
                    }
                }
            } finally {
                closeAll(output);
            }
            copyMetadata(sharedInput, candidates.get(0));
            // Restore the original Nether/End metadata (including level.dat and
            // its spawn coordinates) previously saved in a SHARED sidecar.
            for (int id = 1; id <= 2; id++) {
                var sidecar = DimensionSpawnMetadata.sidecarFolder(sharedInput, id);
                if (Files.isDirectory(sidecar)) copyMetadata(sidecar, candidates.get(id));
            }
            for (int id = 0; id <= 2; id++) {
                DimensionStorageInspector.requireNativeLayout(candidates.get(id), id);
                DimensionActorIntegrity.verify(candidates.get(id), id);
            }
            return Map.copyOf(counts);
        } catch (IOException e) {
            throw new WorldStorageException("Failed to split shared LevelDB into dimension candidates", e);
        }
    }

    public static long mergeToCandidate(Map<Integer, Path> separateInputs, Path candidate) {
        if (!separateInputs.keySet().equals(java.util.Set.of(0, 1, 2))) {
            throw new IllegalArgumentException("Provide Overworld, Nether and End input folders");
        }
        validatePaths(separateInputs.values(), java.util.List.of(candidate));
        for (int id = 0; id <= 2; id++) {
            DimensionStorageInspector.requireNativeLayout(separateInputs.get(id), id);
            DimensionActorIntegrity.verify(separateInputs.get(id), id);
        }
        long count = 0;
        try {
            Files.createDirectories(candidate.resolve("db"));
            try (var output = new Iq80DBFactory().open(candidate.resolve("db").toFile(),
                    new Options().createIfMissing(true))) {
                for (int id = 0; id <= 2; id++) {
                    var folder = separateInputs.get(id);
                    try (var db = new Iq80DBFactory().open(folder.resolve("db").toFile(),
                            new Options().createIfMissing(false)); var it = db.iterator()) {
                        for (it.seekToFirst(); it.hasNext();) {
                            var entry = it.next();
                            var owner = classify(entry.getKey(), Map.of(), false);
                            if (owner != id && owner != -1) {
                                throw new WorldStorageException("Key from " + folder + " belongs to dimension " + owner);
                            }
                            if (owner == -1 && !startsWith(entry.getKey(), ACTOR)) {
                                throw new WorldStorageException("Ambiguous metadata in non-Overworld database");
                            }
                            if (output.get(entry.getKey()) != null) {
                                throw new WorldStorageException("SHARED merge would overwrite an existing LevelDB record");
                            }
                            output.put(entry.getKey(), entry.getValue());
                            count++;
                        }
                    }
                }
            }
            copyMetadata(separateInputs.get(0), candidate);
            // Never discard per-dimension level.dat when merging into SHARED:
            // retain every auxiliary file in the Overworld world's sidecar.
            for (int id = 1; id <= 2; id++) {
                copyMetadata(separateInputs.get(id),
                        DimensionSpawnMetadata.sidecarFolder(candidate, id), true);
            }
            return count;
        } catch (IOException e) {
            throw new WorldStorageException("Failed to stage merged SHARED LevelDB", e);
        }
    }

    private static void validatePaths(Iterable<Path> inputs, Iterable<Path> outputs) {
        var all = new java.util.ArrayList<Path>();
        var sources = new java.util.ArrayList<Path>();
        for (var input : inputs) {
            var p = input.toAbsolutePath().normalize();
            if (!Files.isDirectory(p.resolve("db")) || Files.isSymbolicLink(p)) {
                throw new WorldStorageException("Invalid source LevelDB folder: " + p);
            }
            sources.add(p);
            all.add(p);
        }
        for (var output : outputs) {
            var p = output.toAbsolutePath().normalize();
            if (Files.exists(p)) throw new WorldStorageException("Candidate already exists: " + p);
            all.add(p);
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(i).startsWith(all.get(j)) || all.get(j).startsWith(all.get(i))) {
                    throw new WorldStorageException("Overlapping dimension storage paths");
                }
            }
        }
    }

    private static Map<Long, Integer> collectActorOwners(Path database) throws IOException {
        var owners = new HashMap<Long, Integer>();
        try (var db = new Iq80DBFactory().open(database.toFile(), new Options().createIfMissing(false));
             var iterator = db.iterator()) {
            for (iterator.seekToFirst(); iterator.hasNext();) {
                var item = iterator.next();
                var key = item.getKey();
                if (!startsWith(key, DIGEST)) continue;
                int owner = digestDimension(key);
                var ids = item.getValue();
                if (ids.length % 8 != 0) throw new WorldStorageException("Malformed actor digest");
                for (int offset = 0; offset < ids.length; offset += 8) {
                    var uniqueId = longLE(ids, offset);
                    if (owners.putIfAbsent(uniqueId, owner) != null) {
                        throw new WorldStorageException("Actor belongs to multiple chunk digests");
                    }
                }
            }
        }
        return owners;
    }

    private static int classify(byte[] key, Map<Long, Integer> ownership, boolean isShared) {
        var chunk = DimensionStorageInspector.findDimensionId(key);
        if (chunk != null) {
            if (chunk < 0 || chunk > 2) throw new WorldStorageException("Unrecognized dimension " + chunk);
            return chunk;
        }
        if (startsWith(key, DIGEST)) return digestDimension(key);
        if (startsWith(key, ACTOR)) {
            if (key.length != ACTOR.length + 8) throw new WorldStorageException("Malformed actor key");
            if (isShared) {
                var owner = ownership.get(longLE(key, ACTOR.length));
                if (owner == null) throw new WorldStorageException("Orphan actor record in SHARED database");
                return owner;
            }
            return -1; // caller already knows which independent database owns it
        }
        if (key.length == 9 || key.length == 10 || key.length == 13 || key.length == 14) {
            throw new WorldStorageException("Unknown chunk-shaped key; layout migration rejected");
        }
        return 0; // global world metadata stays with the Overworld
    }

    private static int digestDimension(byte[] key) {
        if (key.length == 12) return 0;
        if (key.length != 16) throw new WorldStorageException("Invalid dimension actor digest key");
        int id = (key[12] & 255) | ((key[13] & 255) << 8) | ((key[14] & 255) << 16) | (key[15] << 24);
        if (id < 1 || id > 2) throw new WorldStorageException("Unknown actor digest dimension " + id);
        return id;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    private static long longLE(byte[] data, int offset) {
        long id = 0;
        for (int i = 0; i < 8; i++) id |= (data[offset + i] & 255L) << (i * 8);
        return id;
    }

    private static void copyMetadata(Path source, Path destination) throws IOException {
        copyMetadata(source, destination, false);
    }

    private static void copyMetadata(Path source, Path destination, boolean replaceExisting) throws IOException {
        try (var walk = Files.walk(source)) {
            for (var file : walk.toList()) {
                if (Files.isSymbolicLink(file)) throw new WorldStorageException("Unexpected link in source metadata");
                var relative = source.relativize(file);
                if (relative.getNameCount() == 0 || relative.getName(0).toString().equals("db")) continue;
                var target = destination.resolve(relative);
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    if (replaceExisting) {
                        // In a subsequent mode switch the Overworld may carry an
                        // older sidecar; the independent dimension's current data
                        // takes precedence, while the old directory is backed up.
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES,
                                StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        }
    }

    private static void closeAll(Map<Integer, DB> stores) {
        for (var db : stores.values()) {
            try {
                db.close();
            } catch (IOException e) {
                throw new WorldStorageException("Failed to close candidate LevelDB", e);
            }
        }
    }
}
