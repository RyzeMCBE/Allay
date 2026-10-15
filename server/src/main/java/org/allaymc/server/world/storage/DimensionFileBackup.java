package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Copies a closed world directory to a brand-new permanent backup or scratch
 * directory, validating the copy's every file against its source.
 */
public final class DimensionFileBackup {
    private DimensionFileBackup() {}

    public static void copyVerified(Path source, Path target) {
        var origin = source.toAbsolutePath().normalize();
        var destination = target.toAbsolutePath().normalize();
        if (!Files.isDirectory(origin) || Files.exists(destination) ||
                origin.startsWith(destination) || destination.startsWith(origin)) {
            throw new WorldStorageException("Unsafe world backup source or destination");
        }
        try (var files = Files.walk(origin)) {
            for (var file : files.toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new WorldStorageException("World backup cannot contain symbolic links: " + file);
                }
                var copied = destination.resolve(origin.relativize(file));
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copied);
                } else if (Files.isRegularFile(file)) {
                    Files.createDirectories(copied.getParent());
                    Files.copy(file, copied, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    throw new WorldStorageException("Unsupported entry during backup: " + file);
                }
            }
            DimensionConversionPreparation.verifyBackup(origin, destination);
        } catch (IOException e) {
            throw new WorldStorageException("Cannot complete verified world backup", e);
        }
    }
}
