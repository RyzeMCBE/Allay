package org.allaymc.server.blockentity.impl;

import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.allaymc.api.component.Component;
import org.allaymc.server.component.ComponentProvider;
import java.util.List;

/** Opaque storage representation for unsupported block entity identifiers. */
public class BlockEntityUnknownImpl extends BlockEntityImpl {
    public BlockEntityUnknownImpl(BlockEntityInitInfo initInfo,
                                  List<ComponentProvider<? extends Component>> componentProviders) {
        super(initInfo, componentProviders);
    }
}
