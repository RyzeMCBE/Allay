package org.allaymc.server.container;

import org.allaymc.api.blockentity.interfaces.BlockEntityEnderChest;
import org.allaymc.api.container.ContainerViewer;
import org.allaymc.api.math.position.Position3i;
import org.allaymc.api.world.Dimension;
import org.allaymc.server.container.impl.EnderChestContainerImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnderChestCloseTest {
    @Test
    void closeListenerCanRestoreNullPositionBeforeNativeCloseFinishes() {
        var dimension = mock(Dimension.class);
        var position = new Position3i(1, 70, 2, dimension);
        var chest = mock(BlockEntityEnderChest.class);
        when(dimension.getBlockEntity(position)).thenReturn(chest);
        when(chest.getViewersCount()).thenReturn(1);
        var container = new EnderChestContainerImpl();
        container.setBlockPos(position);
        container.addCloseListener(viewer -> container.setBlockPos(null));

        assertDoesNotThrow(() -> container.onClose(mock(ContainerViewer.class)));

        assertNull(container.getBlockPos());
        verify(chest).setViewersCount(0);
    }

    @Test
    void detachedVirtualEnderChestCanOpenAndClose() {
        var container = new EnderChestContainerImpl();
        var viewer = mock(ContainerViewer.class);
        assertDoesNotThrow(() -> container.onOpen(viewer));
        assertDoesNotThrow(() -> container.onClose(viewer));
    }
}
