package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Automatic offline SHARED <-> SEPARATE layout transition for one logical
 * world. Uses permanent verified backups, scratch copies, staged LevelDB
 * databases, and a multi-directory recovery journal.
 *
 * <p>Must run before ANY corresponding world storage is opened and never
 * concurrently with other world layout transitions. Every dimension is
 * included even if currently disabled in world-settings.yml, preserving its
 * on-disk data for future re-enabling.</p>
 */
public final class DimensionLayoutTransition {
    private static final long SAFETY_MARGIN = 64L * 1024 * 1024;

    private DimensionLayoutTransition() {}

    public static boolean ensureLayout(Path worldsRoot, String name, String expectedMode,
                                       Map<Integer, Path> dimensionPaths) {
        var root = worldsRoot.toAbsolutePath().normalize();
        if (!name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals("..") ||
                !dimensionPaths.keySet().equals(Set.of(0, 1, 2))) {
            throw new WorldStorageException("Invalid logical world or dimension paths for migration");
        }
        var paths = new HashMap<Integer, Path>();
        for (var entry : dimensionPaths.entrySet()) {
            var p = entry.getValue().toAbsolutePath().normalize();
            if (!p.startsWith(root) || p.equals(root) || Files.isSymbolicLink(p)) {
                throw new WorldStorageException("Dimension path is outside world root: " + p);
            }
            paths.put(entry.getKey(), p);
        }
        for (int a = 0; a <= 2; a++) {
            for (int b = a + 1; b <= 2; b++) {
                if (paths.get(a).startsWith(paths.get(b)) || paths.get(b).startsWith(paths.get(a))) {
                    throw new WorldStorageException("Dimension paths overlap; layout migration is unsafe");
                }
            }
        }

        var journal = root.resolve(".dimension-layout-journal").resolve(name + ".pending");
        DimensionLayoutPromotion.recover(root, journal);
        var shared = paths.get(0);
        var worldIds = DimensionStorageInspector.detectDimensionIds(shared);
        if (!worldIds.stream().allMatch(id -> id >= 0 && id <= 2)) {
            throw new WorldStorageException("Unsupported dimension IDs in primary world: " + worldIds);
        }
        var nonOverworldExists = new HashMap<Integer, Boolean>();
        for (int id = 1; id <= 2; id++) {
            var folder = paths.get(id);
            var ids = DimensionStorageInspector.detectDimensionIds(folder);
            if (!ids.isEmpty() && !ids.equals(Set.of(id))) {
                // An Overworld-authored custom dimension is handled separately
                // by the one-time imported-map converter in SEPARATE mode.
                if (!expectedMode.equalsIgnoreCase("SEPARATE") || !ids.equals(Set.of(0))) {
                    throw new WorldStorageException("Ambiguous dimension data in " + folder + ": " + ids);
                }
            }
            nonOverworldExists.put(id, Files.isDirectory(folder.resolve("db")) && ids.equals(Set.of(id)));
        }
        boolean otherHasData = nonOverworldExists.values().stream().anyMatch(Boolean::booleanValue);
        boolean sharedHasOtherDimensions = worldIds.contains(1) || worldIds.contains(2);

        if (expectedMode.equalsIgnoreCase("SEPARATE")) {
            if (!sharedHasOtherDimensions) return false;
            if (otherHasData || Files.exists(paths.get(1)) || Files.exists(paths.get(2))) {
                throw new WorldStorageException("Both shared and separate dimension folders have data; refusing split");
            }
            transitionSharedToSeparate(root, name, paths, journal);
            return true;
        }
        if (expectedMode.equalsIgnoreCase("SHARED")) {
            if (!otherHasData) return false;
            if (sharedHasOtherDimensions || !Files.isDirectory(shared.resolve("db"))) {
                throw new WorldStorageException("Cannot merge overlapping or missing source dimensions");
            }
            transitionSeparateToShared(root, name, paths, journal);
            return true;
        }
        throw new IllegalArgumentException("Unknown dimension storage mode " + expectedMode);
    }

    private static void transitionSharedToSeparate(Path root, String name, Map<Integer, Path> paths, Path journal) {
        requireSpace(root, List.of(paths.get(0)));
        var backupRoot = newBackupRoot(root, name);
        var sourceBackup = backupRoot.resolve("overworld-original");
        DimensionFileBackup.copyVerified(paths.get(0), sourceBackup);
        var scratch = backupRoot.resolve("scratch-world");
        DimensionFileBackup.copyVerified(sourceBackup, scratch);
        var candidates = candidates(paths);
        DimensionLayoutMigration.splitToCandidates(scratch, candidates);
        verifyOriginals(List.of(paths.get(0)), List.of(sourceBackup));
        var changes = new ArrayList<DimensionLayoutPromotion.Change>();
        for (int id = 0; id <= 2; id++) {
            changes.add(new DimensionLayoutPromotion.Change(paths.get(id), candidates.get(id)));
        }
        DimensionLayoutPromotion.promote(root, journal, changes);
    }

    private static void transitionSeparateToShared(Path root, String name, Map<Integer, Path> paths, Path journal) {
        var originals = new ArrayList<Path>();
        for (int id = 0; id <= 2; id++) {
            if (Files.isDirectory(paths.get(id).resolve("db"))) originals.add(paths.get(id));
        }
        requireSpace(root, originals);
        var backupRoot = newBackupRoot(root, name);
        var backup = new HashMap<Integer, Path>();
        var scratch = new HashMap<Integer, Path>();
        for (int id = 0; id <= 2; id++) {
            var original = paths.get(id);
            var preserved = backupRoot.resolve("dimension-" + id + "-original");
            var input = backupRoot.resolve("dimension-" + id + "-scratch");
            if (Files.isDirectory(original.resolve("db"))) {
                DimensionFileBackup.copyVerified(original, preserved);
                DimensionFileBackup.copyVerified(preserved, input);
                backup.put(id, preserved);
            } else {
                createEmptyLevelDB(input);
            }
            scratch.put(id, input);
        }
        var candidate = stagePath(paths.get(0));
        DimensionLayoutMigration.mergeToCandidate(scratch, candidate);
        for (int id = 0; id <= 2; id++) {
            if (backup.containsKey(id)) verifyOriginals(List.of(paths.get(id)), List.of(backup.get(id)));
        }
        var changes = new ArrayList<DimensionLayoutPromotion.Change>();
        changes.add(new DimensionLayoutPromotion.Change(paths.get(0), candidate));
        for (int id = 1; id <= 2; id++) {
            if (Files.exists(paths.get(id))) {
                changes.add(new DimensionLayoutPromotion.Change(paths.get(id), null));
            }
        }
        DimensionLayoutPromotion.promote(root, journal, changes);
    }

    private static Map<Integer, Path> candidates(Map<Integer, Path> originals) {
        var result = new HashMap<Integer, Path>();
        for (var entry : originals.entrySet()) result.put(entry.getKey(), stagePath(entry.getValue()));
        return result;
    }

    private static Path stagePath(Path input) {
        var stage = input.resolveSibling(input.getFileName() + ".layout-candidate");
        if (Files.exists(stage)) {
            throw new WorldStorageException("Old layout candidate exists; inspect interrupted migration: " + stage);
        }
        return stage;
    }

    private static Path newBackupRoot(Path root, String name) {
        return root.resolve(".dimension-layout-backups").resolve(name).resolve(UUID.randomUUID().toString());
    }

    private static void createEmptyLevelDB(Path folder) {
        try {
            Files.createDirectories(folder.resolve("db"));
            try (var ignored = new Iq80DBFactory().open(folder.resolve("db").toFile(),
                    new Options().createIfMissing(true))) {
                // This dimension has no data; stage a valid empty DB.
            }
        } catch (IOException e) {
            throw new WorldStorageException("Cannot stage empty missing dimension", e);
        }
    }

    private static void verifyOriginals(List<Path> originals, List<Path> backups) {
        for (int i = 0; i < originals.size(); i++) {
            try {
                DimensionConversionPreparation.verifyBackup(originals.get(i), backups.get(i));
            } catch (IOException e) {
                throw new WorldStorageException("World changed during layout conversion", e);
            }
        }
    }

    private static void requireSpace(Path root, List<Path> worlds) {
        long bytes = 0;
        try {
            for (var world : worlds) {
                try (var walk = Files.walk(world)) {
                    for (var file : walk.toList()) {
                        if (Files.isSymbolicLink(file)) throw new WorldStorageException("Unsafe world link: " + file);
                        if (Files.isRegularFile(file)) bytes = Math.addExact(bytes, Files.size(file));
                    }
                }
            }
            var required = Math.addExact(Math.multiplyExact(bytes, 4), SAFETY_MARGIN);
            if (Files.getFileStore(root).getUsableSpace() < required) {
                throw new WorldStorageException("Not enough space for safe SHARED/SEPARATE migration");
            }
        } catch (IOException | ArithmeticException e) {
            throw new WorldStorageException("Cannot verify disk capacity for layout transition", e);
        }
    }
}
