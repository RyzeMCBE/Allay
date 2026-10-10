package org.allaymc.server.blockentity.impl;

import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.allaymc.api.component.Component;
import org.allaymc.server.component.ComponentProvider;
import java.util.List;

/**
 * Bedrock MobSpawner tile: retains its imported configuration in NBT.
 * Actual mob generation is implemented separately from storage compatibility.
 */
public class BlockEntityMobSpawnerImpl extends BlockEntityImpl {
    public BlockEntityMobSpawnerImpl(BlockEntityInitInfo initInfo,
                                    List<ComponentProvider<? extends Component>> componentProviders) {
        super(initInfo, componentProviders);
    }
}
