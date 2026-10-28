package org.allaymc.server.block;

import org.allaymc.api.block.dto.Block;
import org.allaymc.api.block.type.BlockState;

/** Resolves the vanilla connection properties for a block at its actual position. */
public interface BlockConnectionResolver {
    BlockState resolveConnections(Block block);
}
