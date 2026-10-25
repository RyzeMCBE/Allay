package org.allaymc.server.blockentity.impl;

import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.allaymc.api.component.Component;
import org.allaymc.server.component.ComponentProvider;

import java.util.List;

/** Retains daylight-detector tile data; its redstone behavior belongs to the block component. */
public class BlockEntityDaylightDetectorImpl extends BlockEntityImpl {
    public BlockEntityDaylightDetectorImpl(BlockEntityInitInfo initInfo,
                                          List<ComponentProvider<? extends Component>> componentProviders) {
        super(initInfo, componentProviders);
    }
}
