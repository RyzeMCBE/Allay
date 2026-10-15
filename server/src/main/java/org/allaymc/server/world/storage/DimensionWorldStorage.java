package org.allaymc.server.world.storage;

import org.allaymc.api.entity.Entity;
import org.allaymc.api.world.World;
import org.allaymc.api.world.WorldData;
import org.allaymc.api.world.chunk.Chunk;
import org.allaymc.api.world.dimension.DimensionType;
import org.allaymc.api.world.storage.WorldStorage;
import org.joml.Vector3i;
import org.joml.Vector3ic;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Routes each dimension's chunk and entity I/O to an independent world storage.
 * The primary (overworld) storage owns the world's level.dat and its world metadata.
 *
 * <p>Ownership is exclusive: a backend must not be registered for more than one dimension.
 * Callers are responsible for opening the stores and verifying their dimension IDs before
 * constructing this object. This class does not convert imported worlds.</p>
 */
public final class DimensionWorldStorage implements WorldStorage {
    private final WorldStorage primary;
    private final Map<DimensionType, WorldStorage> byDimension;
    private final Map<DimensionType, Vector3ic> importedSpawnPoints;

    public DimensionWorldStorage(WorldStorage primary, Map<DimensionType, WorldStorage> byDimension) {
        this(primary, byDimension, Map.of());
    }

    public DimensionWorldStorage(WorldStorage primary, Map<DimensionType, WorldStorage> byDimension,
                                 Map<DimensionType, Vector3ic> importedSpawnPoints) {
        this.primary = Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(importedSpawnPoints, "importedSpawnPoints");
        if (byDimension == null || byDimension.isEmpty()) {
            throw new IllegalArgumentException("At least the overworld dimension must be provided");
        }
        var unique = new IdentityHashMap<WorldStorage, Boolean>();
        var result = new LinkedHashMap<DimensionType, WorldStorage>();
        for (var entry : byDimension.entrySet()) {
            var dimension = Objects.requireNonNull(entry.getKey(), "dimension");
            var storage = Objects.requireNonNull(entry.getValue(), "storage");
            if (unique.put(storage, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Storage instance shared by multiple dimensions: " + dimension.getIdentifier());
            }
            result.put(dimension, storage);
        }
        if (!unique.containsKey(primary)) {
            throw new IllegalArgumentException("Primary storage is not assigned to a dimension");
        }
        this.byDimension = Map.copyOf(result);
        var saved = new LinkedHashMap<DimensionType, Vector3ic>();
        importedSpawnPoints.forEach((type, pos) -> {
            if (!result.containsKey(type)) throw new IllegalArgumentException("Spawn dimension not enabled: " + type);
            saved.put(type, new Vector3i(Objects.requireNonNull(pos, "spawn")));
        });
        this.importedSpawnPoints = Map.copyOf(saved);
    }

    /**
     * Per-dimension spawn loaded from an imported dimension's own level.dat.
     * Do not substitute the Overworld's global spawn when metadata is absent.
     */
    public Vector3ic getImportedSpawnPoint(DimensionType dimensionType) {
        return importedSpawnPoints.get(dimensionType);
    }

    private WorldStorage forDimension(DimensionType dimensionType) {
        var storage = byDimension.get(Objects.requireNonNull(dimensionType, "dimensionType"));
        if (storage == null) {
            throw new IllegalArgumentException("Dimension is not enabled: " + dimensionType.getIdentifier());
        }
        return storage;
    }

    @Override
    public void setWorld(World world) {
        byDimension.values().forEach(storage -> storage.setWorld(world));
    }

    @Override
    public void tick(long currentTick) {
        byDimension.values().forEach(storage -> storage.tick(currentTick));
    }

    @Override
    public void startBatchWrite() {
        byDimension.values().forEach(WorldStorage::startBatchWrite);
    }

    @Override
    public void flushBatchWrite() {
        byDimension.values().forEach(WorldStorage::flushBatchWrite);
    }

    @Override
    public CompletableFuture<Chunk> readChunk(int chunkX, int chunkZ, DimensionType dimensionType) {
        return forDimension(dimensionType).readChunk(chunkX, chunkZ, dimensionType);
    }

    @Override
    public Chunk readChunkSync(int chunkX, int chunkZ, DimensionType dimensionType) {
        return forDimension(dimensionType).readChunkSync(chunkX, chunkZ, dimensionType);
    }

    @Override
    public CompletableFuture<Void> writeChunk(Chunk chunk) {
        return forDimension(chunk.getDimensionType()).writeChunk(chunk);
    }

    @Override
    public void writeChunkSync(Chunk chunk) {
        forDimension(chunk.getDimensionType()).writeChunkSync(chunk);
    }

    @Override
    public CompletableFuture<Map<Long, Entity>> readEntities(int chunkX, int chunkZ, DimensionType dimensionType) {
        return forDimension(dimensionType).readEntities(chunkX, chunkZ, dimensionType);
    }

    @Override
    public Map<Long, Entity> readEntitiesSync(int chunkX, int chunkZ, DimensionType dimensionType) {
        return forDimension(dimensionType).readEntitiesSync(chunkX, chunkZ, dimensionType);
    }

    @Override
    public CompletableFuture<Void> writeEntities(int chunkX, int chunkZ, DimensionType dimensionType, Map<Long, Entity> entities) {
        return forDimension(dimensionType).writeEntities(chunkX, chunkZ, dimensionType, entities);
    }

    @Override
    public void writeEntitiesSync(int chunkX, int chunkZ, DimensionType dimensionType, Map<Long, Entity> entities) {
        forDimension(dimensionType).writeEntitiesSync(chunkX, chunkZ, dimensionType, entities);
    }

    @Override
    public boolean containChunk(int chunkX, int chunkZ, DimensionType dimensionType) {
        return forDimension(dimensionType).containChunk(chunkX, chunkZ, dimensionType);
    }

    @Override
    public void writeWorldData(WorldData worldData) {
        primary.writeWorldData(worldData);
    }

    @Override
    public WorldData readWorldData() {
        return primary.readWorldData();
    }

    @Override
    public String getName() {
        return primary.getName();
    }

    @Override
    public void shutdown() {
        RuntimeException failure = null;
        for (var storage : byDimension.values()) {
            try {
                storage.shutdown();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
