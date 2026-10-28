package org.allaymc.server.item.impl;

import java.util.List;
import org.allaymc.api.component.Component;
import org.allaymc.api.item.ItemStackInitInfo;
import org.allaymc.api.item.interfaces.ItemIceCrystalStack;
import org.allaymc.server.component.ComponentProvider;

public class ItemIceCrystalStackImpl extends ItemStackImpl implements ItemIceCrystalStack {
    public ItemIceCrystalStackImpl(ItemStackInitInfo initInfo,
            List<ComponentProvider<? extends Component>> componentProviders) {
        super(initInfo, componentProviders);
    }
}
