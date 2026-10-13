package org.allaymc.server.world.storage;

import io.netty.buffer.Unpooled;
import org.allaymc.api.world.storage.WorldStorageException;
import org.allaymc.server.datastruct.palette.Palette;

import java.util.HashMap;
import java.util.Map;

/**
 * Rewrites the absolute biome-section layout and relative heightmap of a Bedrock
 * DATA_3D record when moving an Overworld-built map into a native dimension.
 * Invalid palettes/heights fail the conversion instead of dropping terrain data.
 */
public final class DimensionData3DConverter {
    private static final int HEIGHT_CELLS = 256;
    private DimensionData3DConverter() {}

    public static byte[] remap(byte[] data, int sourceMinY, int sourceMaxY, int targetMinY, int targetMaxY) {
        validateRange(sourceMinY, sourceMaxY);
        validateRange(targetMinY, targetMaxY);
        if (data == null || data.length < HEIGHT_CELLS * 2) {
            throw new WorldStorageException("Invalid DATA_3D heightmap");
        }
        try {
            var input = Unpooled.wrappedBuffer(data);
            var output = Unpooled.buffer(data.length);
            try {
                var rawHeights = new int[HEIGHT_CELLS];
                boolean placeholder = true;
                for (int i = 0; i < HEIGHT_CELLS; i++) {
                    rawHeights[i] = input.readUnsignedShortLE();
                    if (rawHeights[i] != 0) placeholder = false;
                }

                for (int i = 0; i < HEIGHT_CELLS; i++) {
                    int value = rawHeights[i];
                    if (placeholder) {
                        output.writeShortLE(0);
                    } else {
                        long absolute = (long) sourceMinY + value;
                        if (absolute < targetMinY || absolute > targetMaxY) {
                            throw new WorldStorageException("DATA_3D heightmap contains Y " + absolute +
                                    " outside target bounds " + targetMinY + ".." + targetMaxY);
                        }
                        output.writeShortLE((int) (absolute - targetMinY));
                    }
                }

                Map<Integer, Palette<Integer>> sourcePalettes = new HashMap<>();
                Palette<Integer> last = null;
                for (int sectionY = sourceMinY >> 4; sectionY <= sourceMaxY >> 4; sectionY++) {
                    var palette = new Palette<>(0);
                    palette.readBiomeFromStorage(input, id -> id, last);
                    sourcePalettes.put(sectionY, palette);
                    last = palette;
                }
                if (input.isReadable()) {
                    throw new WorldStorageException("DATA_3D biome payload has unexpected trailing data");
                }
                for (int sectionY = targetMinY >> 4; sectionY <= targetMaxY >> 4; sectionY++) {
                    var palette = sourcePalettes.get(sectionY);
                    if (palette == null) palette = new Palette<>(0);
                    palette.writeToStorage(output, id -> id, null);
                }
                var converted = new byte[output.readableBytes()];
                output.readBytes(converted);
                return converted;
            } finally {
                output.release();
                input.release();
            }
        } catch (WorldStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorldStorageException("Invalid DATA_3D biome palette or heightmap", e);
        }
    }

    private static void validateRange(int minY, int maxY) {
        if ((minY & 15) != 0 || (maxY & 15) != 15 || maxY < minY) {
            throw new IllegalArgumentException("Invalid dimension height range: " + minY + ".." + maxY);
        }
    }
}
