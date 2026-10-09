package org.allaymc.server.world.storage.leveldb.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.block.property.type.BlockPropertyType;
import org.allaymc.api.block.type.BlockState;
import org.allaymc.api.block.type.BlockType;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.registry.Registries;
import org.allaymc.api.utils.NBTIO;
import org.allaymc.api.world.biome.BiomeType;
import org.allaymc.api.world.biome.BiomeTypes;
import org.allaymc.api.world.dimension.DimensionType;
import org.allaymc.api.world.dimension.DimensionTypes;
import org.allaymc.server.datastruct.palette.Palette;
import org.allaymc.server.datastruct.palette.PaletteException;
import org.allaymc.server.datastruct.palette.PaletteUtils;
import org.allaymc.server.world.chunk.AllayChunkSection;
import org.allaymc.server.world.storage.leveldb.LevelDBUtils;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.util.stream.LittleEndianDataInputStream;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Codec for serializing/deserializing chunk sections.
 */
@Slf4j
@UtilityClass
public final class ChunkSectionCodec {

    public static byte[] serialize(AllayChunkSection section, int ySection) {
        return LevelDBUtils.withByteBufToArray(buffer -> {
            buffer.writeByte(AllayChunkSection.CURRENT_CHUNK_SECTION_VERSION);
            buffer.writeByte(AllayChunkSection.LAYER_COUNT);
            buffer.writeByte(ySection);
            for (int i = 0; i < AllayChunkSection.LAYER_COUNT; i++) {
                var palette = section.blockLayers()[i];
                palette.compact();
                palette.writeToStorage(buffer, BlockState::getBlockStateNBT);
                palette.setDirty(false);
            }
        });
    }

    /**
     * Deserialize a chunk section from raw bytes.
     *
     * @return the deserialized section, or {@code null} if the sub-chunk version is unknown.
     */
    public static AllayChunkSection deserialize(byte[] data, int sectionY, int chunkX, int chunkZ) {
        var byteBuf = Unpooled.wrappedBuffer(data);
        var subChunkVersion = byteBuf.readByte();
        var layers = AllayChunkSection.LAYER_COUNT;

        switch (subChunkVersion) {
            case 9, 8:
                layers = byteBuf.readByte();
                if (subChunkVersion == 9) {
                    // Extra section y value in version 9
                    byteBuf.readByte();
                }
            case 1:
                AllayChunkSection section;
                if (layers <= AllayChunkSection.LAYER_COUNT) {
                    // This is the normal situation where the chunk section is loaded correctly,
                    // and we use the single-arg constructor of ChunkSection directly to avoid
                    // using Arrays.fill(), which will be slower
                    section = new AllayChunkSection((byte) sectionY);
                } else {
                    // Currently only two layers are used in minecraft, so that might mean this chunk is corrupted
                    // However we can still load it c:
                    log.warn("Loading chunk section ({}, {}, {}) with {} layers, which might mean that this chunk is corrupted!", chunkX, sectionY, chunkZ, layers);
                    @SuppressWarnings("rawtypes") Palette[] palettes = new Palette[layers];
                    Arrays.fill(palettes, new Palette<>(BlockTypes.AIR.getDefaultState()));
                    section = new AllayChunkSection((byte) sectionY, palettes);
                }

                for (int layer = 0; layer < layers; layer++) {
                    var palette = section.blockLayers()[layer];
                    palette.readFromStorage(byteBuf, ChunkSectionCodec::fastBlockStateDeserializer);
                    palette.setDirty(false);
                }
                return section;
            default:
                log.warn("Unknown subchunk version {} at ({}, {}, {})", subChunkVersion, chunkX, sectionY, chunkZ);
                return null;
        }
    }

    private static BlockState fastBlockStateDeserializer(ByteBuf buffer) {
        int blockStateHash;
        NbtMap rawNbt = null;
        try (var bufInputStream = new ByteBufInputStream(buffer);
             var input = new LittleEndianDataInputStream(bufInputStream);
             var nbtInputStream = new NBTInputStream(input)) {
            blockStateHash = PaletteUtils.fastReadBlockStateHash(input, buffer);
            if (blockStateHash == PaletteUtils.HASH_NOT_LATEST) {
                rawNbt = (NbtMap) nbtInputStream.readTag();
            }
        } catch (IOException e) {
            throw new PaletteException(e);
        }

        if (rawNbt == null) {
            var blockState = Registries.BLOCK_STATE_PALETTE.get(blockStateHash);
            if (blockState != null) {
                return blockState;
            }

            // The fast path consumed a current-version state whose hash is unknown to this server.
            // Re-read the same palette entry so it can survive a load/save cycle byte-for-byte.
            buffer.resetReaderIndex();
            rawNbt = readBlockStateNbt(buffer);
        }

        var resolved = NBTIO.getAPI().fromBlockStateNBT(rawNbt);
        if (resolved.getBlockType() != BlockTypes.UNKNOWN) {
            return resolved;
        }

        log.warn("Preserving unrecognized block state while loading chunk section: {}", rawNbt.getString("name"));
        return new PreservedUnknownBlockState(rawNbt);
    }

    private static NbtMap readBlockStateNbt(ByteBuf buffer) {
        try (var bufInputStream = new ByteBufInputStream(buffer);
             var input = new LittleEndianDataInputStream(bufInputStream);
             var nbtInputStream = new NBTInputStream(input)) {
            return (NbtMap) nbtInputStream.readTag();
        } catch (IOException e) {
            throw new PaletteException(e);
        }
    }

    public static AllayChunkSection[] fillNullSections(AllayChunkSection[] sections, DimensionType dimensionType) {
        var defaultBiome = defaultBiomeFor(dimensionType);
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) {
                sections[i] = new AllayChunkSection((byte) (i + dimensionType.minSectionY()), defaultBiome);
            }
        }
        return sections;
    }

    private static BiomeType defaultBiomeFor(DimensionType dimensionType) {
        if (DimensionTypes.NETHER != null && dimensionType.getId() == DimensionTypes.NETHER.getId()) {
            return BiomeTypes.HELL;
        }
        if (DimensionTypes.THE_END != null && dimensionType.getId() == DimensionTypes.THE_END.getId()) {
            return BiomeTypes.THE_END;
        }
        return BiomeTypes.PLAINS;
    }

    /**
     * Runtime view of an unknown block state. Gameplay sees minecraft:unknown, while storage keeps the exact
     * original NBT so newer-version and removed-plugin blocks are not destroyed when a chunk is resaved.
     */
    private record PreservedUnknownBlockState(NbtMap originalNbt) implements BlockState {
        private static BlockState fallback() {
            return BlockTypes.UNKNOWN.getDefaultState();
        }

        @Override
        public BlockType<?> getBlockType() {
            return fallback().getBlockType();
        }

        @Override
        public int blockStateHash() {
            return fallback().blockStateHash();
        }

        @Override
        public long specialValue() {
            return fallback().specialValue();
        }

        @Override
        public Map<BlockPropertyType<?>, BlockPropertyType.BlockPropertyValue<?, ?, ?>> getPropertyValues() {
            return fallback().getPropertyValues();
        }

        @Override
        public BlockState setPropertyValues(List<BlockPropertyType.BlockPropertyValue<?, ?, ?>> propertyValues) {
            return fallback().setPropertyValues(propertyValues);
        }

        @Override
        public <DATATYPE, PROPERTY extends BlockPropertyType<DATATYPE>> DATATYPE getPropertyValue(PROPERTY property) {
            return fallback().getPropertyValue(property);
        }

        @Override
        public BlockState setPropertyValue(BlockPropertyType.BlockPropertyValue<?, ?, ?> propertyValue) {
            return fallback().setPropertyValue(propertyValue);
        }

        @Override
        public <DATATYPE, PROPERTY extends BlockPropertyType<DATATYPE>> BlockState setPropertyValue(PROPERTY property, DATATYPE value) {
            return fallback().setPropertyValue(property, value);
        }

        @Override
        public NbtMap getBlockStateNBT() {
            return originalNbt;
        }

        @Override
        public ItemStack toItemStack() {
            return fallback().toItemStack();
        }
    }
}
