package org.allaymc.server.world.storage;

import org.allaymc.api.world.storage.WorldStorageException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Persistent proof that an offline dimension conversion completed and that
 * its staged LevelDB records have not changed before installation.
 *
 * <p>The digest covers every LevelDB key and value, with length framing,
 * in LevelDB iteration order. A conversion may be promoted only if its
 * marker, record count, target dimension and contents agree. This is a
 * corruption/tampering check, not an authentication mechanism.</p>
 */
public final class DimensionConversionManifest {
    public static final String FILE_NAME = ".allay-dimension-conversion.complete";
    private static final String VERSION = "allay-dimension-conversion-v1";

    private DimensionConversionManifest() {}

    public static void seal(Path candidate, int dimensionId, long expectedRecords) {
        requireSupportedTarget(dimensionId);
        var marker = candidate.resolve(FILE_NAME);
        if (Files.exists(marker)) {
            throw new WorldStorageException("Conversion marker already exists: " + marker);
        }
        var digest = fingerprint(candidate);
        if (digest.records() == 0 || digest.records() != expectedRecords) {
            throw new WorldStorageException("Staged LevelDB record count does not match conversion result");
        }
        DimensionStorageInspector.requireNativeLayout(candidate, dimensionId);
        var content = VERSION + "\n" + dimensionId + "\n" + digest.records() + "\n" + digest.sha256() + "\n";
        try {
            Files.writeString(marker, content, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (var channel = FileChannel.open(marker, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        } catch (IOException e) {
            throw new WorldStorageException("Cannot persist conversion completion marker", e);
        }
    }

    public static void verify(Path candidate, int expectedDimensionId) {
        requireSupportedTarget(expectedDimensionId);
        var marker = candidate.resolve(FILE_NAME);
        if (!Files.isRegularFile(marker) || Files.isSymbolicLink(marker)) {
            throw new WorldStorageException("Missing or unsafe conversion completion marker: " + marker);
        }
        try {
            var lines = Files.readString(marker, StandardCharsets.US_ASCII).split("\n");
            if (lines.length != 4 || !VERSION.equals(lines[0])) {
                throw new WorldStorageException("Unsupported conversion completion marker");
            }
            var id = Integer.parseInt(lines[1]);
            var count = Long.parseLong(lines[2]);
            var hash = lines[3];
            if (id != expectedDimensionId || count <= 0 || !hash.matches("[0-9a-f]{64}")) {
                throw new WorldStorageException("Conversion marker target or fingerprint is invalid");
            }
            var digest = fingerprint(candidate);
            if (digest.records() != count || !digest.sha256().equals(hash)) {
                throw new WorldStorageException("Converted LevelDB was modified after verification");
            }
        } catch (IOException | NumberFormatException e) {
            throw new WorldStorageException("Cannot validate dimension conversion marker", e);
        }
    }

    private static void requireSupportedTarget(int dimensionId) {
        if (dimensionId != 1 && dimensionId != 2) {
            throw new IllegalArgumentException("Only Nether (1) and End (2) conversion targets are supported");
        }
    }

    private static Fingerprint fingerprint(Path worldFolder) {
        var dbFolder = worldFolder.resolve("db");
        if (!Files.isDirectory(dbFolder) || Files.isSymbolicLink(dbFolder)) {
            throw new WorldStorageException("Staged world has no safe LevelDB directory");
        }
        try {
            var md = MessageDigest.getInstance("SHA-256");
            long records = 0;
            try (var db = new Iq80DBFactory().open(dbFolder.toFile(), new Options().createIfMissing(false));
                 var iterator = db.iterator()) {
                for (iterator.seekToFirst(); iterator.hasNext();) {
                    var entry = iterator.next();
                    addFramed(md, entry.getKey());
                    addFramed(md, entry.getValue());
                    records++;
                }
            }
            return new Fingerprint(records, HexFormat.of().formatHex(md.digest()));
        } catch (IOException e) {
            throw new WorldStorageException("Cannot fingerprint staged LevelDB", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void addFramed(MessageDigest digest, byte[] bytes) {
        int len = bytes.length;
        digest.update(new byte[]{(byte) (len >>> 24), (byte) (len >>> 16),
                (byte) (len >>> 8), (byte) len});
        digest.update(bytes);
    }

    private record Fingerprint(long records, String sha256) {}
}
