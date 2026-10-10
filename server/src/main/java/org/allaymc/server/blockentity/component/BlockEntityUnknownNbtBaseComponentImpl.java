package org.allaymc.server.blockentity.component;

import org.allaymc.api.blockentity.BlockEntityInitInfo;

/**
 * Store-only fallback for unrecognized block entities. Do not send an
 * unsupported NBT type to Bedrock clients.
 */
public final class BlockEntityUnknownNbtBaseComponentImpl extends BlockEntityLegacyNbtBaseComponentImpl {

    public BlockEntityUnknownNbtBaseComponentImpl(BlockEntityInitInfo initInfo) {
        super(initInfo);
    }

    @Override
    public boolean sendToClient() {
        return false;
    }
}
