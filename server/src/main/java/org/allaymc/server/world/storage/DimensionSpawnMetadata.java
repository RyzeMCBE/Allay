package org.allaymc.server.world.storage;

import org.allaymc.api.world.dimension.DimensionType;
import org.allaymc.api.world.storage.WorldStorageException;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.joml.Vector3i;
import org.joml.Vector3ic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Storage-independent per-dimension spawn metadata preserved when switching
 * between SHARED and SEPARATE. A SHARED world has only one normal level.dat;
 * optional original End/Nether level.dat files live in dedicated sidecar folders.
 */
public final class DimensionSpawnMetadata {
    public static final String FOLDER = ".allay-dimension-metadata";

    private DimensionSpawnMetadata() {}

    public static Path sidecarFolder(Path sharedWorld, int dimensionId) {
        return sharedWorld.resolve(FOLDER).resolve(dimensionId == 1 ? "nether" : dimensionId == 2 ? "end" : "unknown");
    }

    public static Vector3ic readSavedSpawn(Path sharedWorld, DimensionType type) {
        if (type.getId() != 1 && type.getId() != 2) return null;
        var path = sidecarFolder(sharedWorld, type.getId()).resolve("level.dat");
        if (!Files.isRegularFile(path)) return null;
        if (Files.isSymbolicLink(path)) throw new WorldStorageException("Unsafe custom dimension spawn metadata");
        try {
            var bytes = Files.readAllBytes(path);
            if (bytes.length < 8) throw new WorldStorageException("Corrupt Bedrock level.dat header");
            var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            header.getInt();
            int size = header.getInt();
            if (size < 0 || size != bytes.length - 8) {
                throw new WorldStorageException("Incorrect imported level.dat size");
            }
            try (var input = NbtUtils.createReaderLE(new ByteArrayInputStream(bytes, 8, size))) {
                var nbt = (NbtMap) input.readTag();
                if (nbt == null || !nbt.containsKey("SpawnX") ||
                        !nbt.containsKey("SpawnY") || !nbt.containsKey("SpawnZ")) {
                    throw new WorldStorageException("Imported dimension metadata has no spawnpoint");
                }
                return new Vector3i(nbt.getInt("SpawnX"), nbt.getInt("SpawnY"), nbt.getInt("SpawnZ"));
            }
        } catch (IOException | ClassCastException e) {
            throw new WorldStorageException("Cannot load saved dimension spawn metadata: " + path, e);
        }
    }
}
