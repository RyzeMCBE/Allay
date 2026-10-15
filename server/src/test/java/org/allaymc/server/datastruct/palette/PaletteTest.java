package org.allaymc.server.datastruct.palette;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import org.allaymc.server.datastruct.bitarray.BitArrayVersion;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author daoge_cmd
 */
class PaletteTest {

    @Test
    void testGeneric() {
        var e0 = new Entry(0);
        var palette = new Palette<>(e0, BitArrayVersion.V0);
        assertEquals(e0, palette.get(0));

        var e1 = new Entry(1);
        palette.set(1, e1);
        assertEquals(BitArrayVersion.V1, palette.getVersion());
        assertEquals(e1, palette.get(1));

        var e2 = new Entry(2);
        palette.set(2, e2);
        assertEquals(BitArrayVersion.V2, palette.getVersion());
        assertEquals(e2, palette.get(2));
    }

    @Test
    void testOneEntryOnly() {
        var e0 = new Entry(0);
        var e1 = new Entry(1);
        var palette = new Palette<>(e0, BitArrayVersion.V0);
        palette.set(1, e1);
        palette.set(1, e0);
        // Bit array version won't downgrade
        assertEquals(BitArrayVersion.V1, palette.getVersion());
        // But the palette should be one entry only
        assertTrue(palette.oneEntryOnly());
    }

    @Test
    void testStorage() {
        var e0 = new Entry(0);
        var e1 = new Entry(1);
        var p0 = new Palette<>(e0, BitArrayVersion.V0);
        p0.set(1, e1);

        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
        p0.writeToStorage(buffer, PDSerializerImpl.INSTANCE);

        var p1 = new Palette<>(e0, BitArrayVersion.V0);
        p1.readFromStorage(buffer, PDDeserializerImpl.INSTANCE);
        assertEquals(e0, p1.get(0));
        assertEquals(e1, p1.get(1));
    }

    @Test
    void testStorageV2() {
        var e0 = new Entry(0);
        var e1 = new Entry(1);
        var e2 = new Entry(2);

        var p0 = new Palette<>(e0, BitArrayVersion.V0);
        p0.set(1, e1);
        p0.set(2, e2);
        ByteBuf b0 = ByteBufAllocator.DEFAULT.buffer();
        p0.writeToStorage(b0, PDSerializerImpl.INSTANCE);

        var p1 = new Palette<>(e0, BitArrayVersion.V2);
        p1.set(1, e1);
        p1.set(2, e2);
        ByteBuf b1 = ByteBufAllocator.DEFAULT.buffer();
        p1.writeToStorage(b1, PDSerializerImpl.INSTANCE);

        var bytes0 = ByteBufUtil.getBytes(b0);
        var bytes1 = ByteBufUtil.getBytes(b1);
        assertArrayEquals(bytes0, bytes1);
    }

    @Test
    void readsPocketMine4CustomItemApiZeroBitPaletteWithExtraLength() {
        var defaultEntry = new Entry(47);
        var written = new Palette<>(defaultEntry, BitArrayVersion.V0);
        var vanilla = ByteBufAllocator.DEFAULT.buffer();
        try {
            written.writeToStorage(vanilla, PDSerializerImpl.INSTANCE);
            assertEquals(0, vanilla.getUnsignedByte(0) >> 1);
            assertEquals(10, vanilla.getUnsignedByte(1)); // NBT Compound
            var legacy = ByteBufAllocator.DEFAULT.buffer();
            try {
                legacy.writeByte(vanilla.getUnsignedByte(0));
                legacy.writeIntLE(1); // Illegal in vanilla, accepted in PM4.
                legacy.writeBytes(vanilla, 1, vanilla.writerIndex() - 1);
                // The next palette begins immediately after the legacy one.
                // Reading the first must not consume even one byte of it.
                written.writeToStorage(legacy, PDSerializerImpl.INSTANCE);

                var recovered = new Palette<>(new Entry(0));
                recovered.readFromStorage(legacy, PDDeserializerImpl.INSTANCE);
                assertEquals(defaultEntry, recovered.get(0));
                var second = new Palette<>(new Entry(0));
                second.readFromStorage(legacy, PDDeserializerImpl.INSTANCE);
                assertEquals(defaultEntry, second.get(0));
                assertFalse(legacy.isReadable());
            } finally {
                legacy.release();
            }
        } finally {
            vanilla.release();
        }
    }

    @Test
    void rejectsIncorrectSingleStateSizeInsteadOfSkippingUnrecognizedBytes() {
        var input = ByteBufAllocator.DEFAULT.buffer();
        try {
            input.writeByte(0);
            input.writeIntLE(2); // Not a supported legacy size.
            input.writeByte(10);
            var palette = new Palette<>(new Entry(0));
            assertThrows(PaletteException.class,
                    () -> palette.readFromStorage(input, PDDeserializerImpl.INSTANCE));
        } finally {
            input.release();
        }
    }

    record Entry(int id) {
    }

    static class PDSerializerImpl implements NBTSerializer<Entry> {
        static PDSerializerImpl INSTANCE = new PDSerializerImpl();

        @Override
        public NbtMap serialize(Entry value) {
            return NbtMap.builder().putInt("id", value.id).build();
        }
    }

    static class PDDeserializerImpl implements NBTDeserializer<Entry> {
        static PDDeserializerImpl INSTANCE = new PDDeserializerImpl();

        @Override
        public Entry deserialize(ByteBuf buffer) {
            try (var reader = NbtUtils.createReaderLE(new ByteBufInputStream(buffer))) {
                var nbt = (NbtMap) reader.readTag();
                return new Entry(nbt.getInt("id"));
            } catch (IOException e) {
                throw new PaletteException(e);
            }
        }
    }
}