package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.allaymc.server.world.dimension.DimensionId;
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
        return ensureLayout(worldsRoot, name, expectedMode, dimensionPaths, "FAIL");
    }

    /**
     * Explicitly opt in to using already-imported SEPARATE Nether/End worlds
     * instead of the corresponding dimensions embedded in the shared source.
     * The default FAIL policy continues to reject all conflicting layouts.
     */
    public static boolean ensureLayout(Path worldsRoot, String name, String expectedMode,
                                       Map<Integer, Path> dimensionPaths, String conflictPolicy) {
        if (!"FAIL".equalsIgnoreCase(conflictPolicy) &&
                !"PREFER_SEPARATE".equalsIgnoreCase(conflictPolicy)) {
            throw new WorldStorageException("Unknown dimension conflict policy " + conflictPolicy);
        }
        if ("PREFER_SEPARATE".equalsIgnoreCase(conflictPolicy) &&
                !"SEPARATE".equalsIgnoreCase(expectedMode)) {
            throw new WorldStorageException("PREFER_SEPARATE requires dimension-storage-mode: SEPARATE");
        }
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

        // Already supplied independent dimensions are authoritative in SEPARATE.
        // Do not inspect, extract, or rewrite embedded Nether/End records in
        // world/db: old Bedrock worlds can retain these even when custom maps
        // exist in their own folders. Only inspect the two configured stores.
        if ("SEPARATE".equalsIgnoreCase(expectedMode)) {
            var nether = paths.get(1);
            var end = paths.get(2);
            boolean hasNether = Files.isDirectory(nether.resolve("db"));
            boolean hasEnd = Files.isDirectory(end.resolve("db"));
            if (hasNether && hasEnd) {
                for (int id = 1; id <= 2; id++) {
                    var path = paths.get(id);
                    var ids = DimensionStorageInspector.detectDimensionIds(path);
                    if (!ids.isEmpty() && !ids.equals(Set.of(id)) && !ids.equals(Set.of(0))) {
                        throw new WorldStorageException("Independent " +
                                (id == 1 ? "Nether" : "End") + " at " + path +
                                " has unsupported dimension IDs " + ids +
                                ". Expected " + id + " or imported Overworld ID 0.");
                    }
                }
                org.slf4j.LoggerFactory.getLogger(DimensionLayoutTransition.class).info(
                        "SEPARATE world {}: loading configured Nether {} and End {} directly. " +
                        "Embedded dimensions in the Overworld LevelDB are ignored and preserved.",
                        name, nether, end);
                return false;
            }
            if (hasNether != hasEnd || Files.exists(nether) || Files.exists(end)) {
                throw new WorldStorageException("SEPARATE world '" + name +
                        "' has incomplete independent dimension folders. Nether: " + nether +
                        " (db=" + hasNether + "), End: " + end + " (db=" + hasEnd + "). " +
                        "Restore both custom maps from their original backups before starting. " +
                        "No shared-world data was migrated.");
            }
            // Neither independent folder exists. Preserve the existing
            // SHARED-to-SEPARATE migration path for users explicitly switching
            // modes on a formerly shared world.
        }

        var shared = paths.get(0);
        var worldIds = DimensionStorageInspector.detectDimensionIds(shared);
        if (!worldIds.stream().allMatch(id -> id >= 0 && id <= 2)) {
            throw new WorldStorageException("Unsupported dimension IDs in primary world: " + worldIds);
        }
        var nonOverworldExists = new HashMap<Integer, Boolean>();
        for (int id = 1; id <= 2; id++) {
            var folder = paths.get(id);
            var ids = DimensionStorageInspector.detectDimensionIds(folder);
            if (ids.equals(Set.of(0)) && expectedMode.equalsIgnoreCase("SHARED") && !worldIds.contains(id)) {
                // Merge a custom Nether/End authored as Overworld by first
                // converting it with its own permanent backup. This lets a
                // user import a custom End directly into SHARED mode.
                var dim = id == 1 ? DimensionId.NETHER : DimensionId.THE_END;
                DimensionStorageAutoConverter.ensureNativeLayout(root, name, folder, id,
                        dim.getDefaultMinHeight(), dim.getDefaultMaxHeight());
                ids = DimensionStorageInspector.detectDimensionIds(folder);
            }
            if (!ids.isEmpty() && !ids.equals(Set.of(id))) {
                // In SEPARATE mode the one-time converter runs after layout
                // preflight. Otherwise, preserve the folder and fail closed.
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
                if ("PREFER_SEPARATE".equalsIgnoreCase(conflictPolicy)) {
                    transitionPreferSeparate(root, name, paths, journal, worldIds);
                    return true;
                }
                var existingFolders = new ArrayList<String>();
                for (int id = 1; id <= 2; id++) {
                    var folder = paths.get(id);
                    if (!Files.exists(folder)) continue;
                    var ids = DimensionStorageInspector.detectDimensionIds(folder);
                    existingFolders.add((id == 1 ? "Nether" : "End") + "=" + folder +
                            " (chunk dimension IDs: " + ids + ")");
                }
                throw new WorldStorageException("Cannot safely migrate world '" + name +
                        "' from SHARED to SEPARATE. Its shared LevelDB at " + shared +
                        " already contains dimension IDs " + worldIds +
                        ", but the target folders already exist: " + existingFolders +
                        ". No split was performed. Keep both sources and their backups. " +
                        "For an isolated test, configure distinct, unused destination paths; " +
                        "do not delete or overwrite the existing dimension folders.");
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

    /**
     * Resolve a shared Overworld which also contains old Nether/End chunks, if
     * the administrator has provided two independent custom dimensions.
     *
     * The supplied Nether and End are authoritative. This procedure NEVER
     * writes either of those folders. We archive the original shared world,
     * including its discarded embedded dimensions, in a verified backup and
     * preserve the extracted embedded records separately for inspection.
     * The independent custom End may still require the normal one-time
     * Overworld-ID -> End-ID conversion after this transaction completes.
     */
    private static void transitionPreferSeparate(Path root, String name, Map<Integer, Path> paths,
                                                 Path journal, Set<Integer> sharedIds) {
        if (!sharedIds.contains(0)) {
            throw new WorldStorageException("Cannot prioritize imported dimensions without Overworld chunk records");
        }
        for (int id = 1; id <= 2; id++) {
            var source = paths.get(id);
            if (!Files.isDirectory(source.resolve("db")) || Files.isSymbolicLink(source)) {
                throw new WorldStorageException("PREFER_SEPARATE requires an existing independent " +
                        (id == 1 ? "Nether" : "End") + " LevelDB: " + source);
            }
            var ids = DimensionStorageInspector.detectDimensionIds(source);
            if (!ids.equals(Set.of(id)) && !ids.equals(Set.of(0))) {
                throw new WorldStorageException("PREFER_SEPARATE cannot identify " + source +
                        ", dimension IDs " + ids + "; expected native " + id + " or imported 0");
            }
        }
        requireSpace(root, List.of(paths.get(0)));
        var backupRoot = newBackupRoot(root, name);
        var originalBackup = backupRoot.resolve("shared-original");
        DimensionFileBackup.copyVerified(paths.get(0), originalBackup);
        var scratch = backupRoot.resolve("shared-scratch");
        DimensionFileBackup.copyVerified(originalBackup, scratch);

        // Extract all three parts from a disposable copy. The embedded Nether
        // and End are stored *inside the backup*, never over imported worlds.
        var candidates = Map.of(
                0, stagePath(paths.get(0)),
                1, backupRoot.resolve("embedded-nether-not-active"),
                2, backupRoot.resolve("embedded-end-not-active")
        );
        DimensionLayoutMigration.splitToCandidates(scratch, candidates);
        verifyOriginals(List.of(paths.get(0)), List.of(originalBackup));

        // Verify that custom source LevelDBs did not change while the shared
        // source was being processed. No custom DB is opened for write here.
        for (int id = 1; id <= 2; id++) {
            var ids = DimensionStorageInspector.detectDimensionIds(paths.get(id));
            if (!ids.equals(Set.of(id)) && !ids.equals(Set.of(0))) {
                throw new WorldStorageException("Imported dimension changed during reconciliation: " + paths.get(id));
            }
        }
        DimensionLayoutPromotion.promote(root, journal,
                List.of(new DimensionLayoutPromotion.Change(paths.get(0), candidates.get(0))));
        org.slf4j.LoggerFactory.getLogger(DimensionLayoutTransition.class).info(
                "Reconciled world {} using imported Nether and End. " +
                "The old shared LevelDB and extracted embedded dimensions are preserved in {}",
                name, backupRoot);
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
        if (!Files.exists(stage, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return stage;
        }
        // An earlier failed conversion may have left an incomplete candidate.
        // Never delete, overwrite or reuse it: it may contain recoverable data.
        // The layout recovery journal is checked before this method is called.
        // Prepare the next attempt at a fresh, unique path instead.
        for (int attempts = 0; attempts < 16; attempts++) {
            var fresh = input.resolveSibling(input.getFileName() +
                    ".layout-candidate-" + UUID.randomUUID());
            if (!Files.exists(fresh, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                org.slf4j.LoggerFactory.getLogger(DimensionLayoutTransition.class)
                        .warn("Preserving previous layout candidate {}. " +
                                "New conversion will use {} (original data unchanged).", stage, fresh);
                return fresh;
            }
        }
        throw new WorldStorageException("Cannot allocate a new, unused layout candidate for " + input);
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
