package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.allaymc.server.world.dimension.DimensionId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/**
 * One-time offline import of a custom Nether/End authored as Overworld.
 *
 * <p>Only invoked for an explicitly configured SEPARATE layout, before the
 * world's LevelDB connections have been opened. Mixed or unidentified formats
 * fail closed. A verified permanent backup is retained outside the source,
 * and the converted candidate is promoted through the recovery journal.</p>
 */
public final class DimensionStorageAutoConverter {
    private static final long SAFETY_MARGIN = 64L * 1024 * 1024;

    private DimensionStorageAutoConverter() {}

    public static boolean ensureNativeLayout(Path worldsRoot, String logicalWorld,
                                             Path dimensionFolder, int targetDimensionId,
                                             int minY, int maxY) {
        var source = dimensionFolder.toAbsolutePath().normalize();
        var ids = DimensionStorageInspector.detectDimensionIds(source);
        if (ids.isEmpty() || ids.equals(Set.of(targetDimensionId))) {
            DimensionStorageInspector.requireNativeLayout(source, targetDimensionId);
            return false;
        }

        if (targetDimensionId == 0 || !ids.equals(Set.of(0)) ||
                (targetDimensionId != 1 && targetDimensionId != 2)) {
            throw new WorldStorageException("Cannot automatically import ambiguous dimension IDs " + ids +
                    " as target " + targetDimensionId + " in " + source);
        }
        if (!logicalWorld.matches("[A-Za-z0-9_.-]+") || logicalWorld.equals(".") || logicalWorld.equals("..")) {
            throw new WorldStorageException("Unsafe world name during dimension conversion");
        }
        var root = worldsRoot.toAbsolutePath().normalize();
        if (!source.startsWith(root) || Files.isSymbolicLink(source) ||
                !Files.isDirectory(source.resolve("db"))) {
            throw new WorldStorageException("Unsafe custom dimension directory: " + source);
        }

        var candidate = source.resolveSibling(source.getFileName() + ".allay-conversion-candidate");
        if (Files.exists(candidate) || Files.exists(DimensionConversionPromotion.journalPath(source))
                || Files.exists(DimensionConversionPromotion.previousPath(source))) {
            throw new WorldStorageException("An earlier dimension conversion requires manual recovery: " + source);
        }

        long bytes = estimateSourceSize(source);
        try {
            long required = Math.addExact(Math.multiplyExact(bytes, 4), SAFETY_MARGIN);
            if (Files.getFileStore(source).getUsableSpace() < required) {
                throw new WorldStorageException("Insufficient disk space for verified backup and staged conversion of " + source);
            }
        } catch (IOException | ArithmeticException e) {
            throw new WorldStorageException("Cannot validate free disk space for " + source, e);
        }

        // Stable, unique backup destination. Never delete or reuse a previous backup.
        var backup = root.resolve(".dimension-backups").resolve(logicalWorld)
                .resolve(source.getFileName() + "-original-" + UUID.randomUUID());
        if (backup.startsWith(source) || Files.exists(backup)) {
            throw new WorldStorageException("Unsafe backup location: " + backup);
        }
        DimensionConversionPreparation.prepare(source, backup, candidate,
                DimensionId.OVERWORLD.getId(), targetDimensionId,
                DimensionId.OVERWORLD.getDefaultMinHeight(),
                DimensionId.OVERWORLD.getDefaultMaxHeight(), minY, maxY);
        DimensionConversionManifest.verify(candidate, targetDimensionId);
        DimensionConversionPromotion.promote(source, backup, candidate, targetDimensionId);
        DimensionStorageInspector.requireNativeLayout(source, targetDimensionId);
        return true;
    }

    private static long estimateSourceSize(Path source) {
        try (var paths = Files.walk(source)) {
            long size = 0;
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new WorldStorageException("Symbolic links are not supported during conversion: " + path);
                }
                if (Files.isRegularFile(path)) size = Math.addExact(size, Files.size(path));
            }
            return size;
        } catch (IOException | ArithmeticException e) {
            throw new WorldStorageException("Cannot assess custom dimension size", e);
        }
    }
}
