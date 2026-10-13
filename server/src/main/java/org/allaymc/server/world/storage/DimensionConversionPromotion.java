package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Offline promotion of an already prepared and verified dimension folder.
 *
 * <p>Two atomic directory renames are guarded by a durable pending journal.
 * A crash between the renames is recovered by restoring the original folder
 * before any LevelDB is opened. The independently verified backup and the
 * retired original are retained; this class never deletes world data.</p>
 *
 * <p>Call only while the dimension and its LevelDB are closed. Candidate and
 * original must reside on the same filesystem. Unsupported atomic renames fail
 * closed, with the pending journal recoverable on next startup.</p>
 */
public final class DimensionConversionPromotion {
    private static final String JOURNAL_HEADER = "allay-dimension-promotion-v1";
    private DimensionConversionPromotion() {}

    public static void promote(Path source, Path backup, Path candidate, int targetDimensionId) {
        var original = source.toAbsolutePath().normalize();
        var saved = backup.toAbsolutePath().normalize();
        var staged = candidate.toAbsolutePath().normalize();
        var previous = previousPath(original);
        var journal = journalPath(original);

        if (original.equals(saved) || original.equals(staged) || saved.equals(staged)
                || original.startsWith(saved) || original.startsWith(staged)
                || saved.startsWith(original) || staged.startsWith(original)
                || saved.startsWith(staged) || staged.startsWith(saved)) {
            throw new IllegalArgumentException("World, backup and candidate must use disjoint paths");
        }
        if (!Files.isDirectory(original) || !Files.isDirectory(saved.resolve("db"))
                || !Files.isDirectory(staged.resolve("db"))
                || Files.exists(previous) || Files.exists(journal)) {
            throw new WorldStorageException("Conversion is not ready, or an earlier promotion needs recovery");
        }
        for (var path : new Path[]{original, saved, staged}) {
            if (Files.isSymbolicLink(path)) {
                throw new WorldStorageException("Symbolic links are not allowed in conversion paths: " + path);
            }
        }
        try {
            if (!Files.getFileStore(original).equals(Files.getFileStore(staged))) {
                throw new WorldStorageException("Candidate must be on the same filesystem as the original");
            }
            DimensionStorageInspector.requireNativeLayout(staged, targetDimensionId);
            // Reject promotion if the source changed since backup preparation.
            // Backups are trusted only when their full file trees still match.
            DimensionConversionPreparation.verifyBackup(original, saved);
            // Commit intent before touching the original directory. On an error,
            // the same recovery path is used as after an unexpected process exit.
            Files.writeString(journal, journalContents(original), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (var channel = FileChannel.open(journal, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(original, previous, StandardCopyOption.ATOMIC_MOVE);
                Files.move(staged, original, StandardCopyOption.ATOMIC_MOVE);
                // The converted world is now installed. Removing the journal commits
                // the promotion. Retired original and backup remain as safeguards.
                Files.delete(journal);
            } catch (IOException | RuntimeException e) {
                try {
                    recoverIfInterrupted(original);
                } catch (RuntimeException recoveryFailure) {
                    e.addSuppressed(recoveryFailure);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new WorldStorageException("Failed to promote the converted dimension; check the recovery journal", e);
        }
    }

    /**
     * Called during startup before attempting to open the dimension's LevelDB.
     * Returns true when a pending promotion was detected and recovered.
     *
     * <p>A completed promotion has no journal and will never be rolled back.
     * A failed candidate is preserved alongside the world for investigation.</p>
     */
    public static boolean recoverIfInterrupted(Path source) {
        var original = source.toAbsolutePath().normalize();
        var journal = journalPath(original);
        if (!Files.exists(journal)) return false;
        var previous = previousPath(original);
        var failed = failedPath(original);
        try {
            if (Files.isSymbolicLink(journal) ||
                    !journalContents(original).equals(Files.readString(journal, StandardCharsets.UTF_8))) {
                throw new WorldStorageException("Unrecognized conversion journal: " + journal);
            }
            if (Files.exists(previous)) {
                if (Files.exists(original)) {
                    if (Files.exists(failed)) {
                        throw new WorldStorageException("Cannot recover; failed-world destination already exists: " + failed);
                    }
                    Files.move(original, failed, StandardCopyOption.ATOMIC_MOVE);
                }
                Files.move(previous, original, StandardCopyOption.ATOMIC_MOVE);
            } else if (!Files.exists(original)) {
                throw new WorldStorageException("Conversion interrupted and the original world cannot be located: " + original);
            }
            Files.delete(journal);
            return true;
        } catch (IOException e) {
            throw new WorldStorageException("Failed to recover interrupted dimension conversion: " + original, e);
        }
    }

    static Path journalPath(Path original) {
        return original.resolveSibling(original.getFileName() + ".dimension-conversion.pending");
    }

    static Path previousPath(Path original) {
        return original.resolveSibling(original.getFileName() + ".dimension-conversion.previous");
    }

    static Path failedPath(Path original) {
        return original.resolveSibling(original.getFileName() + ".dimension-conversion.failed");
    }

    static String journalContents(Path original) {
        return JOURNAL_HEADER + "\n" + original.toAbsolutePath().normalize() + "\n";
    }
}
