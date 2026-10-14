package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Multi-directory transaction for changing dimension layouts while LevelDB
 * is offline. Archives all old directories and installs staged ones with
 * recoverable atomic renames. The journal is outside the moved directories.
 *
 * <p>This class does not perform the DB conversion or decide when to migrate.
 * Callers must verify backups and staged records first.</p>
 */
public final class DimensionLayoutPromotion {
    private static final String HEADER = "allay-dimension-layout-v1";
    private DimensionLayoutPromotion() {}

    /** Null candidate means archive this dimension (used in SEPARATE->SHARED). */
    public record Change(Path target, Path candidate) {}

    private record Step(Path target, Path candidate, Path previous, Path failed,
                        boolean existed, String fingerprint) {}

    public static void promote(Path root, Path journal, List<Change> changes) {
        var base = root.toAbsolutePath().normalize();
        var journalPath = journal.toAbsolutePath().normalize();
        if (!journalPath.startsWith(base) || Files.exists(journalPath) || changes.isEmpty()) {
            throw new WorldStorageException("Unsafe or already active layout migration journal");
        }
        String id = UUID.randomUUID().toString();
        var steps = new ArrayList<Step>();
        for (var change : changes) {
            var target = change.target().toAbsolutePath().normalize();
            var candidate = change.candidate() == null ? null : change.candidate().toAbsolutePath().normalize();
            var previous = target.resolveSibling(target.getFileName() + ".layout-previous-" + id);
            var failed = target.resolveSibling(target.getFileName() + ".layout-failed-" + id);
            if (!target.startsWith(base) || target.equals(base) ||
                    (candidate != null && (!candidate.startsWith(base) || candidate.equals(base))) ||
                    Files.exists(previous) || Files.exists(failed) ||
                    Files.isSymbolicLink(target) || (candidate != null && Files.isSymbolicLink(candidate)) ||
                    (candidate != null && !Files.isDirectory(candidate.resolve("db")))) {
                throw new WorldStorageException("Invalid layout transaction path: " + target);
            }
            if (candidate == null && !Files.exists(target)) {
                throw new WorldStorageException("Nothing to archive for " + target);
            }
            if (candidate != null && candidate.equals(target)) {
                throw new WorldStorageException("Candidate and target must differ");
            }
            var existed = Files.exists(target);
            steps.add(new Step(target, candidate, previous, failed, existed,
                    candidate == null ? "-" : fingerprint(candidate)));
        }
        var paths = new ArrayList<Path>();
        for (var step : steps) {
            paths.add(step.target());
            if (step.candidate() != null) paths.add(step.candidate());
        }
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                if (paths.get(i).startsWith(paths.get(j)) || paths.get(j).startsWith(paths.get(i))) {
                    throw new WorldStorageException("Overlapping paths in layout transaction");
                }
            }
        }
        try {
            Files.createDirectories(journalPath.getParent());
            Files.writeString(journalPath, encode(base, steps), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (var handle = FileChannel.open(journalPath, StandardOpenOption.WRITE)) {
                handle.force(true);
            }
            try {
                for (var step : steps) {
                    if (step.candidate() != null && !step.fingerprint().equals(fingerprint(step.candidate()))) {
                        throw new WorldStorageException("Candidate changed after layout preparation");
                    }
                    if (step.existed()) Files.move(step.target(), step.previous(), StandardCopyOption.ATOMIC_MOVE);
                    if (step.candidate() != null) {
                        Files.move(step.candidate(), step.target(), StandardCopyOption.ATOMIC_MOVE);
                        if (!step.fingerprint().equals(fingerprint(step.target()))) {
                            throw new WorldStorageException("Installed layout differs from staged layout");
                        }
                    }
                }
                Files.delete(journalPath);
            } catch (IOException | RuntimeException e) {
                try {
                    recover(root, journal);
                } catch (RuntimeException recovery) {
                    e.addSuppressed(recovery);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new WorldStorageException("Layout promotion interrupted; inspect recovery journal", e);
        }
    }

    public static boolean recover(Path root, Path journal) {
        var base = root.toAbsolutePath().normalize();
        var marker = journal.toAbsolutePath().normalize();
        if (!Files.exists(marker)) return false;
        if (!marker.startsWith(base) || Files.isSymbolicLink(marker)) {
            throw new WorldStorageException("Unsafe layout recovery journal");
        }
        try {
            var steps = decode(Files.readString(marker, StandardCharsets.UTF_8), base);
            for (int i = steps.size() - 1; i >= 0; i--) {
                var step = steps.get(i);
                if (Files.exists(step.previous())) {
                    if (Files.exists(step.target())) {
                        if (Files.exists(step.failed())) {
                            throw new WorldStorageException("Cannot recover; failed target already exists: " + step.failed());
                        }
                        Files.move(step.target(), step.failed(), StandardCopyOption.ATOMIC_MOVE);
                    }
                    Files.move(step.previous(), step.target(), StandardCopyOption.ATOMIC_MOVE);
                } else if (!step.existed() && step.candidate() != null &&
                        Files.exists(step.target()) && !Files.exists(step.candidate())) {
                    // A new target was installed and had no original directory.
                    if (Files.exists(step.failed())) throw new WorldStorageException("Recovery conflict");
                    Files.move(step.target(), step.failed(), StandardCopyOption.ATOMIC_MOVE);
                } else if (step.existed() && !Files.exists(step.target())) {
                    throw new WorldStorageException("Original directory missing during recovery: " + step.target());
                }
            }
            Files.delete(marker);
            return true;
        } catch (IOException e) {
            throw new WorldStorageException("Cannot recover interrupted layout migration", e);
        }
    }

    private static String encode(Path root, List<Step> steps) {
        var text = new StringBuilder(HEADER).append('\n').append(escaped(root)).append('\n');
        for (var step : steps) {
            text.append(escaped(step.target())).append('|')
                    .append(step.candidate() == null ? "-" : escaped(step.candidate())).append('|')
                    .append(escaped(step.previous())).append('|')
                    .append(escaped(step.failed())).append('|')
                    .append(step.existed() ? "1" : "0").append('|')
                    .append(step.fingerprint()).append('\n');
        }
        return text.toString();
    }

    private static List<Step> decode(String text, Path root) {
        var lines = text.split("\n");
        if (lines.length < 3 || !HEADER.equals(lines[0]) || !root.equals(unescape(lines[1]))) {
            throw new WorldStorageException("Unknown layout recovery journal");
        }
        var steps = new ArrayList<Step>();
        for (int i = 2; i < lines.length; i++) {
            var fields = lines[i].split("\\|", -1);
            if (fields.length != 6) throw new WorldStorageException("Corrupted migration step");
            var target = unescape(fields[0]);
            var candidate = fields[1].equals("-") ? null : unescape(fields[1]);
            var previous = unescape(fields[2]);
            var failed = unescape(fields[3]);
            for (var path : new Path[]{target, previous, failed}) {
                if (!path.startsWith(root) || path.equals(root) || Files.isSymbolicLink(path)) {
                    throw new WorldStorageException("Untrusted recovery path: " + path);
                }
            }
            if (candidate != null && (!candidate.startsWith(root) || Files.isSymbolicLink(candidate))) {
                throw new WorldStorageException("Untrusted staged recovery path");
            }
            if (!fields[4].equals("0") && !fields[4].equals("1")) {
                throw new WorldStorageException("Invalid recovery state");
            }
            steps.add(new Step(target, candidate, previous, failed, fields[4].equals("1"), fields[5]));
        }
        return steps;
    }

    private static String escaped(Path path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                path.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Path unescape(String text) {
        try {
            return Path.of(new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8))
                    .toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            throw new WorldStorageException("Invalid encoded journal path", e);
        }
    }

    private static String fingerprint(Path folder) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var walk = Files.walk(folder)) {
                for (var file : walk.sorted().toList()) {
                    if (Files.isSymbolicLink(file)) throw new WorldStorageException("Layout staging contains link");
                    var relative = folder.relativize(file);
                    digest.update(relative.toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) (Files.isDirectory(file) ? 0 : 1));
                    if (Files.isRegularFile(file)) {
                        try (InputStream input = Files.newInputStream(file)) {
                            var buffer = new byte[65536];
                            int n;
                            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new WorldStorageException("Cannot fingerprint layout candidate", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
