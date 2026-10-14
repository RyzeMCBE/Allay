package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Offline preparation for a dimension conversion. Produces an untouched
 * backup and a fully separate candidate, but does NOT replace the active
 * world. Promotion must be implemented with an explicit recovery journal.
 *
 * The server must ensure that no world storage has the source open.
 */
public final class DimensionConversionPreparation {
    private DimensionConversionPreparation() {}

    public static long prepare(Path source, Path backup, Path candidate,
                               int sourceId, int targetId,
                               int sourceMinY, int sourceMaxY, int targetMinY, int targetMaxY) {
        var original = source.toAbsolutePath().normalize();
        var saved = backup.toAbsolutePath().normalize();
        var prepared = candidate.toAbsolutePath().normalize();
        if (original.equals(saved) || original.equals(prepared) || saved.equals(prepared) ||
                saved.startsWith(original) || prepared.startsWith(original) ||
                original.startsWith(saved) || original.startsWith(prepared) ||
                saved.startsWith(prepared) || prepared.startsWith(saved)) {
            throw new IllegalArgumentException("Source, backup and candidate must be disjoint paths");
        }
        if (!Files.isDirectory(original.resolve("db")) || Files.exists(saved) || Files.exists(prepared)) {
            throw new WorldStorageException("Missing source LevelDB or backup/staging destination already exists");
        }
        try {
            copyTree(original, saved);
            verifyBackup(original, saved);
            long converted = DimensionLevelDBConverter.copyAsDimension(
                    saved, prepared, sourceId, targetId,
                    sourceMinY, sourceMaxY, targetMinY, targetMaxY
            );
            // Copy world metadata and other non-DB files from the verified
            // original backup; the converted database lives only in candidate/db.
            try (var files = Files.walk(saved)) {
                for (var file : files.toList()) {
                    var relative = saved.relativize(file);
                    if (relative.getNameCount() > 0 && relative.getName(0).toString().equals("db")) continue;
                    var destination = prepared.resolve(relative);
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            DimensionConversionManifest.seal(prepared, targetId, converted);
            return converted;
        } catch (IOException e) {
            throw new WorldStorageException("Failed to prepare backed-up dimension conversion", e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var files = Files.walk(from)) {
            for (var file : files.toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new WorldStorageException("Conversion does not follow symbolic links: " + file);
                }
                var dest = to.resolve(from.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(dest);
                else Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /**
     * Validate both trees, including unexpected additional files and symbolic links.
     * Re-run immediately before promoting the candidate, while both LevelDB stores
     * remain closed, to ensure that the preserved original matches its backup.
     */
    public static void verifyBackup(Path source, Path copied) throws IOException {
        verifyTreeSide(source, copied);
        verifyTreeSide(copied, source);
    }

    private static void verifyTreeSide(Path source, Path copied) throws IOException {
        try (var files = Files.walk(source)) {
            for (var file : files.toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new WorldStorageException("Symbolic link encountered in conversion backup: " + file);
                }
                var corresponding = copied.resolve(source.relativize(file));
                if (Files.isSymbolicLink(corresponding)) {
                    throw new WorldStorageException("Symbolic link encountered in conversion backup: " + corresponding);
                }
                if (Files.isDirectory(file)) {
                    if (!Files.isDirectory(corresponding)) {
                        throw new WorldStorageException("Missing backup directory " + corresponding);
                    }
                } else if (!Files.isRegularFile(file) || !Files.isRegularFile(corresponding) ||
                           Files.size(file) != Files.size(corresponding) ||
                           !Arrays.equals(digest(file), digest(corresponding))) {
                    throw new WorldStorageException("Backup validation failed for " + file);
                }
            }
        }
    }

    private static byte[] digest(Path path) throws IOException {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                var buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
