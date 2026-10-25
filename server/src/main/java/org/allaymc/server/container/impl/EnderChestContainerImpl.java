package org.allaymc.server.container.impl;

import org.allaymc.api.blockentity.component.BlockEntityEnderChestBaseComponent;
import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.container.ContainerViewer;

/**
 * @author IWareQ
 */
public class EnderChestContainerImpl extends BlockContainerImpl {
    public EnderChestContainerImpl() {
        super(ContainerTypes.ENDER_CHEST);
    }

    @Override
    public void onOpen(ContainerViewer viewer) {
        var openedPosition = blockPos;
        super.onOpen(viewer);

        if (openedPosition != null && openedPosition.dimension() != null
                && openedPosition.dimension().getBlockEntity(openedPosition) instanceof BlockEntityEnderChestBaseComponent enderChest) {
            enderChest.setViewersCount(enderChest.getViewersCount() + 1);
        }
    }

    @Override
    public void onClose(ContainerViewer viewer) {
        // Virtual-container close listeners restore the original (possibly null) position.
        var closedPosition = blockPos;
        super.onClose(viewer);

        if (closedPosition != null && closedPosition.dimension() != null
                && closedPosition.dimension().getBlockEntity(closedPosition) instanceof BlockEntityEnderChestBaseComponent enderChest) {
            enderChest.setViewersCount(enderChest.getViewersCount() - 1);
        }
    }
}
