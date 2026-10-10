package org.allaymc.server.world.storage.leveldb.codec;

import io.netty.buffer.Unpooled;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.registry.Registries;
import org.allaymc.api.utils.hash.HashUtils;
import org.allaymc.api.world.biome.BiomeType;
import org.allaymc.api.world.biome.BiomeTypes;
import org.allaymc.server.datastruct.palette.Palette;
import org.allaymc.server.world.chunk.AllayChunkBuilder;
import org.allaymc.server.world.chunk.AllayChunkSection;
import org.allaymc.server.world.chunk.AllayUnsafeChunk;
import org.allaymc.server.world.chunk.HeightMap;
import org.allaymc.server.world.storage.leveldb.LevelDBUtils;

/**
 * Codec for serializing/deserializing height maps and biome data.
 *
 * @see <a href="https://github.com/bedrock-dev/bedrock-level/blob/main/src/include/data_3d.h#L115">Biome 3d</a>
 */
@Slf4j
@UtilityClass
public final class HeightAndBiomeCodec {

    public static final int HEIGHTMAP_SIZE = 256;

    /**
     * Serialize height map and 3D biome data to bytes.
     * <p>
     * Bedrock Edition 3d-data saves the height map starting from index 0,
     * so adjustments are made here to accommodate the world's minimum height.
     */
    public static byte[] serialize(AllayUnsafeChunk chunk) {
        return LevelDBUtils.withByteBufToArray(heightAndBiomesBuffer -> {
            var dimensionType = chunk.getDimensionType();
            // Serialize height map
            for (var height : chunk.calculateAndGetHeightMap().getHeights()) {
                heightAndBiomesBuffer.writeShortLE(height - dimensionType.getMinHeight());
            }

            // Serialize biomes
            Palette<BiomeType> lastPalette = null;
            for (int y = dimensionType.minSectionY(); y <= dimensionType.maxSectionY(); y++) {
                AllayChunkSection section = chunk.getSection(y);
                section.biomes().compact();
                section.biomes().writeToStorage(heightAndBiomesBuffer, BiomeType::getId, lastPalette);
                // TODO: Fix client crash due to biome copy flag
                // lastPalette = section.biomes();
            }
        });
    }

    /**
     * Deserialize 3D height map and biome data (DATA_3D format).
     */
    public static void deserialize(byte[] data, AllayChunkBuilder builder) {
        var heightAndBiomesBuffer = Unpooled.wrappedBuffer(data);
        var dimensionType = builder.getDimensionType();

        // Height map
        short[] heights = new short[HEIGHTMAP_SIZE];
        boolean placeholderHeightMap = true;
        for (int i = 0; i < HEIGHTMAP_SIZE; i++) {
            int storedHeight = heightAndBiomesBuffer.readUnsignedShortLE();
            if (storedHeight != 0) placeholderHeightMap = false;
            heights[i] = (short) (storedHeight + dimensionType.getMinHeight());
        }
        builder.heightMap(new HeightMap(heights));

        // Biomes
        Palette<BiomeType> lastPalette = null;
        var minSectionY = dimensionType.minSectionY();
        for (int y = minSectionY; y <= dimensionType.maxSectionY(); y++) {
            AllayChunkSection section = builder.getSections()[y - minSectionY];
            if (section == null) {
                continue;
            }

            section.biomes().readBiomeFromStorage(heightAndBiomesBuffer, HeightAndBiomeCodec::getBiomeByIdNonNull, lastPalette);
            lastPalette = section.biomes();
        }

        if (placeholderHeightMap) restorePlaceholderHeightMap(builder);
    }

    /**
     * Reconstruct heights from decoded block sections when DATA_3D contains
     * PocketMine-MP's zero-filled placeholder. Never modify block data.
     */
    private static void restorePlaceholderHeightMap(AllayChunkBuilder builder) {
        var dimension = builder.getDimensionType();
        var sections = builder.getSections();
        short[] heights = new short[HEIGHTMAP_SIZE];
        int minSectionY = dimension.minSectionY();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                short height = (short) dimension.getMinHeight();
                search:
                for (int sectionY = dimension.maxSectionY(); sectionY >= minSectionY; sectionY--) {
                    var section = sections[sectionY - minSectionY];
                    if (section == null || section.isAirSection()) continue;
                    for (int localY = 15; localY >= 0; localY--) {
                        if (section.getBlockState(x, localY, z, 0).getBlockType() != BlockTypes.AIR) {
                            height = (short) ((sectionY << 4) + localY);
                            break search;
                        }
                    }
                }
                heights[HeightMap.computeIndex(x, z)] = height;
            }
        }
        builder.heightMap(new HeightMap(heights));
    }

    /**
     * Deserialize legacy 2D height map and biome data (DATA_2D format).
     */
    public static void deserializeOld(byte[] data, AllayChunkBuilder builder) {
        var heightAndBiomesBuffer = Unpooled.wrappedBuffer(data);
        short[] heights = new short[HEIGHTMAP_SIZE];
        for (int i = 0; i < HEIGHTMAP_SIZE; i++) {
            heights[i] = heightAndBiomesBuffer.readShortLE();
        }
        builder.heightMap(new HeightMap(heights));

        byte[] biomes = new byte[HEIGHTMAP_SIZE];
        heightAndBiomesBuffer.readBytes(biomes);

        var dimensionType = builder.getDimensionType();
        var minSectionY = dimensionType.minSectionY();
        for (int y = minSectionY; y <= dimensionType.maxSectionY(); y++) {
            var section = builder.getSections()[y - minSectionY];
            if (section == null) {
                continue;
            }

            var biomePalette = section.biomes();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int sy = 0; sy < 16; sy++) {
                        biomePalette.set(HashUtils.hashChunkSectionXYZ(x, sy, z), getBiomeByIdNonNull(Byte.toUnsignedInt(biomes[x + 16 * z])));
                    }
                }
            }
        }
    }

    public static BiomeType getBiomeByIdNonNull(int id) {
        // The biome registry is map-backed: unknown IDs return null instead of
        // throwing ArrayIndexOutOfBoundsException. Never put null into a palette.
        var biome = Registries.BIOMES.getByK1(id);
        if (biome == null) {
            log.warn("Unknown biome id: {}; using plains as a fallback", id);
            return BiomeTypes.PLAINS;
        }
        return biome;
    }
}
