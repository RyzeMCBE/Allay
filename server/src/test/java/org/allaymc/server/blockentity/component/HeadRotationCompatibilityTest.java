package org.allaymc.server.blockentity.component;

import org.allaymc.api.block.data.CompassRoseDirection;
import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.allaymc.api.blockentity.type.BlockEntityTypes;
import org.allaymc.api.world.Dimension;
import org.allaymc.server.world.storage.leveldb.codec.BlockEntityCodec;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(AllayTestExtension.class)
class HeadRotationCompatibilityTest {
    @Test
    void missingAndInvalidRotationCanBeSentAndSavedDuringChunkUnload() {
        for (var nbt : List.of(NbtMap.EMPTY, NbtMap.builder().putString("Rotation", "invalid").build(),
                NbtMap.builder().putFloat("Rotation", Float.NaN).build())) {
            var head = BlockEntityTypes.HEAD.createBlockEntity(BlockEntityInitInfo.builder()
                    .dimension(mock(Dimension.class)).pos(3,65,7).nbt(nbt).build());
            assertEquals(CompassRoseDirection.SOUTH, head.getDirection());
            assertEquals(180f, head.saveNBT().getFloat("Rotation"));
            assertNotNull(BlockEntityCodec.serialize(List.of(head)));
        }
    }

    @Test
    void importsLegacyRotationIndicesAndNumericYawWithoutLosingOrientation() {
        for (int index = 0; index < 16; index++) {
            var head = BlockEntityTypes.HEAD.createBlockEntity(BlockEntityInitInfo.builder()
                    .dimension(mock(Dimension.class)).nbt(NbtMap.builder().putByte("Rot", (byte) index).build()).build());
            assertEquals(CompassRoseDirection.from(index), head.getDirection());
            var restored = BlockEntityTypes.HEAD.createBlockEntity(BlockEntityInitInfo.builder()
                    .dimension(mock(Dimension.class)).nbt(head.saveNBT()).build());
            assertEquals(head.getDirection(), restored.getDirection());
        }
        var head = BlockEntityTypes.HEAD.createBlockEntity(BlockEntityInitInfo.builder()
                .dimension(mock(Dimension.class)).nbt(NbtMap.builder().putInt("Rotation",90).build()).build());
        assertEquals(CompassRoseDirection.EAST, head.getDirection());
    }
}
