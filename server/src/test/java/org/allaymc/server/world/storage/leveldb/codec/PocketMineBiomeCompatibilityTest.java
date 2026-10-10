package org.allaymc.server.world.storage.leveldb.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.world.biome.BiomeTypes;
import org.allaymc.api.world.dimension.DimensionTypes;
import org.allaymc.server.datastruct.palette.Palette;
import org.allaymc.server.datastruct.palette.PaletteException;
import org.allaymc.server.world.chunk.AllayChunkBuilder;
import org.allaymc.server.world.chunk.AllayChunkSection;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(AllayTestExtension.class)
class PocketMineBiomeCompatibilityTest {
    private static AllayChunkSection[] sections() {
        var dim = DimensionTypes.OVERWORLD;
        var sections = new AllayChunkSection[dim.chunkSectionCount()];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new AllayChunkSection((byte) (i + dim.minSectionY()));
        }
        return sections;
    }

    private static AllayChunkBuilder builder(AllayChunkSection[] sections) {
        return new AllayChunkBuilder().chunkX(0).chunkZ(0)
                .dimensionType(DimensionTypes.OVERWORLD).sections(sections);
    }

    private static byte[] v0Biomes(boolean pocketMine, boolean copyLast) {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeZero(512);
            for (int i = 0; i < DimensionTypes.OVERWORLD.chunkSectionCount(); i++) {
                if (copyLast && i != 0) {
                    buf.writeByte(0xfe);
                    continue;
                }
                buf.writeByte(pocketMine ? 0 : 1);
                buf.writeIntLE(BiomeTypes.FOREST.getId());
            }
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    @Test
    void pocketMineV0Loads() {
        var sections = sections();
        var builder = builder(sections);
        HeightAndBiomeCodec.deserialize(v0Biomes(true, false), builder);
        for (var section : sections) {
            assertEquals(BiomeTypes.FOREST, section.getBiomeType(0, 0, 0));
            assertEquals(BiomeTypes.FOREST, section.getBiomeType(15, 15, 15));
        }
        assertEquals(DimensionTypes.OVERWORLD.getMinHeight(), builder.getHeightMap().get(0, 0));
    }

    @Test
    void allayV0StillLoads() {
        var sections = sections();
        HeightAndBiomeCodec.deserialize(v0Biomes(false, false), builder(sections));
        assertEquals(BiomeTypes.FOREST, sections[0].getBiomeType(0, 0, 0));
    }

    @Test
    void copyPreviousBiomePaletteLoads() {
        var sections = sections();
        HeightAndBiomeCodec.deserialize(v0Biomes(true, true), builder(sections));
        assertEquals(BiomeTypes.FOREST, sections[sections.length - 1].getBiomeType(0, 0, 0));
    }

    @Test
    void pocketMineV1TwoBiomesLoad() {
        ByteBuf buf = Unpooled.buffer();
        byte[] data;
        try {
            buf.writeZero(512);
            buf.writeByte(2);
            for (int word = 0; word < 128; word++) buf.writeIntLE(word == 0 ? 2 : 0);
            buf.writeIntLE(2);
            buf.writeIntLE(BiomeTypes.FOREST.getId());
            buf.writeIntLE(BiomeTypes.DESERT.getId());
            for (int i = 1; i < DimensionTypes.OVERWORLD.chunkSectionCount(); i++) {
                buf.writeByte(0);
                buf.writeIntLE(BiomeTypes.FOREST.getId());
            }
            data = ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
        var sections = sections();
        HeightAndBiomeCodec.deserialize(data, builder(sections));
        assertEquals(BiomeTypes.FOREST, sections[0].biomes().get(0));
        assertEquals(BiomeTypes.DESERT, sections[0].biomes().get(1));
    }

    @Test
    void placeholderHeightMapRecomputed() {
        var sections = sections();
        sections[(80 >> 4) - DimensionTypes.OVERWORLD.minSectionY()]
                .setBlockState(2, 80 & 15, 3, BlockTypes.STONE.getDefaultState(), 0);
        var builder = builder(sections);
        HeightAndBiomeCodec.deserialize(v0Biomes(true, false), builder);
        assertEquals(80, builder.getHeightMap().get(2, 3));
        assertEquals(DimensionTypes.OVERWORLD.getMinHeight(), builder.getHeightMap().get(0, 0));
    }

    @Test
    void strictIntegerReaderStillRejectsNbtHeader() {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeByte(0);
            buf.writeIntLE(BiomeTypes.FOREST.getId());
            var palette = new Palette<>(BiomeTypes.PLAINS);
            assertThrows(PaletteException.class,
                    () -> palette.readFromStorage(buf, HeightAndBiomeCodec::getBiomeByIdNonNull, null));
        } finally {
            buf.release();
        }
    }

    @Test
    void copyPreviousCannotBeFirst() {
        ByteBuf buf = Unpooled.buffer();
        byte[] data;
        try {
            buf.writeZero(512);
            buf.writeByte(0xfe);
            data = ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
        assertThrows(PaletteException.class, () -> HeightAndBiomeCodec.deserialize(data, builder(sections())));
    }
}
