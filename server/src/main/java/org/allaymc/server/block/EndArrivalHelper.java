package org.allaymc.server.block;

import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.math.location.Location3d;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.world.World;
import org.allaymc.server.world.storage.DimensionWorldStorage;
import org.allaymc.server.world.storage.DimensionSpawnMetadata;
import org.joml.Vector3i;
import org.joml.Vector3ic;

import java.util.Optional;

/**
 * Safe destination search for custom End maps.
 *
 * <p>Run position searches and block writes on the destination dimension's tick
 * thread, after the surrounding chunks have finished loading. No existing
 * blocks are cleared or overwritten. A landing platform is built only in a
 * completely empty 5x5 region and only if no nearby safe floor is available.</p>
 */
public final class EndArrivalHelper {
    private static final int PLATFORM_RADIUS = 2;
    private static final int SEARCH_RADIUS = 6;
    private static final int VERTICAL_SEARCH = 12;
    private static final Vector3ic VANILLA_END_ENTRY = new Vector3i(100, 50, 0);

    private EndArrivalHelper() {}

    public static Vector3ic preferredSpawn(World world, Dimension destination) {
        var storage = world.getWorldStorage();
        if (storage instanceof DimensionWorldStorage separate) {
            var imported = separate.getImportedSpawnPoint(destination.getDimensionType());
            if (imported != null && insideDimension(destination, imported.y())) {
                return new Vector3i(imported);
            }
        }
        var sidecar = DimensionSpawnMetadata.readSavedSpawn(
                java.nio.file.Path.of("worlds").resolve(world.getName()), destination.getDimensionType());
        if (sidecar != null && insideDimension(destination, sidecar.y())) {
            return new Vector3i(sidecar);
        }
        return new Vector3i(VANILLA_END_ENTRY);
    }

    public static int searchRadius() {
        return SEARCH_RADIUS;
    }

    /**
     * Returns the safe arrival point, creating a platform only over empty space.
     * An empty Optional means teleportation must be cancelled safely.
     */
    public static Optional<Location3d> findOrCreateArrival(Dimension dimension, Vector3ic preferred) {
        int x = preferred.x(), z = preferred.z();
        int min = dimension.getDimensionType().getMinHeight() + 1;
        int max = dimension.getDimensionType().getMaxHeight() - 2;
        int y = Math.clamp(preferred.y(), min, max);
        if (isSafeStanding(dimension, x, y, z)) {
            return Optional.of(arrival(dimension, x, y, z));
        }

        // Look for a walkable surface near the map's saved spawn, not near a
        // hard-coded Vanilla spawn if this map has a custom one.
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int height = 0; height <= VERTICAL_SEARCH; height++) {
                        int above = y + height;
                        if (above <= max && isSafeStanding(dimension, x + dx, above, z + dz)) {
                            return Optional.of(arrival(dimension, x + dx, above, z + dz));
                        }
                        int below = y - height;
                        if (height != 0 && below >= min &&
                                isSafeStanding(dimension, x + dx, below, z + dz)) {
                            return Optional.of(arrival(dimension, x + dx, below, z + dz));
                        }
                    }
                }
            }
        }

        // Do not clear or replace anything from a custom End. If any part of
        // the proposed landing volume is occupied, refuse to build a platform.
        if (!canBuildPlatform(dimension, x, y, z)) return Optional.empty();
        var obsidian = BlockTypes.OBSIDIAN.getDefaultState();
        int floorY = y - 1;
        for (int bx = x - PLATFORM_RADIUS; bx <= x + PLATFORM_RADIUS; bx++) {
            for (int bz = z - PLATFORM_RADIUS; bz <= z + PLATFORM_RADIUS; bz++) {
                if (!dimension.setBlockState(bx, floorY, bz, obsidian)) {
                    return Optional.empty();
                }
            }
        }
        if (!isSafeStanding(dimension, x, y, z)) return Optional.empty();
        return Optional.of(arrival(dimension, x, y, z));
    }

    /**
     * Strict standing check for configured End -> Overworld return points.
     * Unlike End arrival, this never creates blocks in the player's HCF spawn.
     * Call on the target dimension thread after loading its chunk.
     */
    public static boolean isSafeConfiguredArrival(Dimension dimension, Location3d destination) {
        return isSafeStanding(dimension, (int) Math.floor(destination.x()),
                (int) Math.floor(destination.y()), (int) Math.floor(destination.z()));
    }

    private static Location3d arrival(Dimension dimension, int x, int y, int z) {
        return new Location3d(x + 0.5, y, z + 0.5, dimension);
    }

    private static boolean insideDimension(Dimension dim, int y) {
        return y > dim.getDimensionType().getMinHeight()
                && y + 1 <= dim.getDimensionType().getMaxHeight();
    }

    private static boolean isSafeStanding(Dimension dimension, int x, int y, int z) {
        if (!insideDimension(dimension, y)) return false;
        var floor = dimension.getBlockState(x, y - 1, z);
        var floorType = floor.getBlockType();
        if (!floor.getBlockStateData().isSolid() ||
                floorType == BlockTypes.MAGMA || floorType == BlockTypes.CACTUS) {
            return false;
        }
        return isAir(dimension, x, y, z) && isAir(dimension, x, y + 1, z);
    }

    private static boolean canBuildPlatform(Dimension dimension, int x, int y, int z) {
        if (!insideDimension(dimension, y + 1)) return false;
        for (int bx = x - PLATFORM_RADIUS; bx <= x + PLATFORM_RADIUS; bx++) {
            for (int bz = z - PLATFORM_RADIUS; bz <= z + PLATFORM_RADIUS; bz++) {
                for (int by = y - 1; by <= y + 2; by++) {
                    if (!isAir(dimension, bx, by, bz)) return false;
                }
            }
        }
        return true;
    }

    private static boolean isAir(Dimension dimension, int x, int y, int z) {
        return dimension.getBlockState(x, y, z, 0).getBlockType() == BlockTypes.AIR
                && dimension.getBlockState(x, y, z, 1).getBlockType() == BlockTypes.AIR;
    }
}
