package org.allaymc.server.blockentity;

import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.allaymc.api.blockentity.type.BlockEntityTypes;
import org.allaymc.api.world.Dimension;
import org.allaymc.server.blockentity.component.BlockEntityDaylightDetectorBaseComponentImpl;
import org.allaymc.server.blockentity.impl.BlockEntityImpl;
import org.allaymc.testutils.AllayTestExtension;
import org.joml.Vector3ic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(AllayTestExtension.class)
class DaylightDetectorCompatibilityTest {
    @Test
    void loadedDetectorStartsOneUpdateChainAndRemainsClientCompatible() {
        var dimension = mock(Dimension.class,RETURNS_DEEP_STUBS);
        when(dimension.getBlockState(any(Vector3ic.class))).thenReturn(BlockTypes.DAYLIGHT_DETECTOR.getDefaultState());
        var tile = BlockEntityTypes.DAYLIGHT_DETECTOR.createBlockEntity(
                BlockEntityInitInfo.builder().dimension(dimension).pos(1,65,2).build());
        var base = (BlockEntityDaylightDetectorBaseComponentImpl)((BlockEntityImpl)tile).getBaseComponent();
        base.tick(1); base.tick(2);
        verify(dimension.getBlockUpdateManager(),times(1)).scheduleBlockUpdateInDelay(any(Vector3ic.class),eq(1L));
        assertTrue(base.sendToClient());
    }
}
