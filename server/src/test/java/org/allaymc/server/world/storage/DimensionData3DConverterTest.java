package org.allaymc.server.world.storage;

import io.netty.buffer.Unpooled;
import org.allaymc.api.world.storage.WorldStorageException;
import org.allaymc.server.datastruct.palette.Palette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionData3DConverterTest {
    private static byte[] sample(int minY, int maxY, int absoluteHeight) {
        var out = Unpooled.buffer();
        try {
            for (int i = 0; i < 256; i++) out.writeShortLE(absoluteHeight - minY);
            for (int y = minY >> 4; y <= maxY >> 4; y++) {
                new Palette<>(y == 0 ? 10 : 1).writeToStorage(out, id -> id, null);
            }
            var bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    @Test
    void convertsHeightAndRetainsOverlappingBiomeSections() {
        var source = sample(-64, 319, 63);
        var result = DimensionData3DConverter.remap(source, -64, 319, 0, 255);
        var input = Unpooled.wrappedBuffer(result);
        try {
            for (int i = 0; i < 256; i++) assertEquals(63, input.readUnsignedShortLE());
            for (int y = 0; y <= 15; y++) {
                var palette = new Palette<>(0);
                palette.readBiomeFromStorage(input, id -> id, null);
                assertEquals(y == 0 ? 10 : 1, palette.get(0));
            }
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    @Test
    void refusesOutOfBoundsHeight() {
        var source = sample(-64, 319, 270);
        assertThrows(WorldStorageException.class,
                () -> DimensionData3DConverter.remap(source, -64, 319, 0, 255));
    }

    @Test
    void refusesTruncatedPayload() {
        assertThrows(WorldStorageException.class,
                () -> DimensionData3DConverter.remap(new byte[511], -64, 319, 0, 255));
    }
}
