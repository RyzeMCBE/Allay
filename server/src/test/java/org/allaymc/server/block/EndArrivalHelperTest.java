package org.allaymc.server.block;

import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.world.dimension.DimensionTypes;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.joml.Vector3i;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class EndArrivalHelperTest {

    @Test
    void usesExistingSafeFloorWithoutConstructingAnyPlatform() {
        var dim = dimensionWithBlocks(Map.of("100:49:0", BlockTypes.OBSIDIAN.getDefaultState()));
        var arrival = EndArrivalHelper.findOrCreateArrival(dim, new Vector3i(100, 50, 0));
        assertTrue(arrival.isPresent());
        assertEquals(50, arrival.orElseThrow().y());
        verify(dim, never()).setBlockState(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void buildsPlatformOnlyWhenTheVolumeIsEmpty() {
        var dim = dimensionWithBlocks(Map.of());
        var arrival = EndArrivalHelper.findOrCreateArrival(dim, new Vector3i(100, 50, 0));
        assertTrue(arrival.isPresent());
        verify(dim, times(25)).setBlockState(anyInt(), eq(49), anyInt(), any());
    }

    @Test
    void refusesToOverwriteCustomEndBuilds() {
        var dim = dimensionWithBlocks(Map.of("102:49:0", BlockTypes.OBSIDIAN.getDefaultState()));
        // No safe nearby landing directly above our obstruction? The obstruction
        // itself is a safe standing floor, so the helper must use it, not rewrite.
        var arrival = EndArrivalHelper.findOrCreateArrival(dim, new Vector3i(100, 50, 0));
        assertTrue(arrival.isPresent());
        verify(dim, never()).setBlockState(anyInt(), anyInt(), anyInt(), any());
    }

    private Dimension dimensionWithBlocks(Map<String, org.allaymc.api.block.type.BlockState> initial) {
        var blocks = new HashMap<>(initial);
        Dimension dim = mock(Dimension.class, CALLS_REAL_METHODS);
        when(dim.getDimensionType()).thenReturn(DimensionTypes.THE_END);
        doAnswer(call -> {
            var key = call.getArgument(0) + ":" + call.getArgument(1) + ":" + call.getArgument(2);
            return call.<Integer>getArgument(3) == 0
                    ? blocks.getOrDefault(key, BlockTypes.AIR.getDefaultState())
                    : BlockTypes.AIR.getDefaultState();
        }).when(dim).getBlockState(anyInt(), anyInt(), anyInt(), anyInt());
        doAnswer(call -> {
            var key = call.getArgument(0) + ":" + call.getArgument(1) + ":" + call.getArgument(2);
            blocks.put(key, call.getArgument(3));
            return true;
        }).when(dim).setBlockState(anyInt(), anyInt(), anyInt(), any());
        return dim;
    }
}
