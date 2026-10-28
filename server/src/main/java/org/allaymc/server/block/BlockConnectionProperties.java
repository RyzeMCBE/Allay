package org.allaymc.server.block;

import org.allaymc.api.block.data.BlockFace;
import org.allaymc.api.block.property.type.BooleanPropertyType;
import org.allaymc.api.block.property.type.EnumPropertyType;
import org.allaymc.api.block.property.type.BlockPropertyTypes;

/** Maps a block face to the corresponding 1.26.60 connection-state property. */
public final class BlockConnectionProperties {
    private BlockConnectionProperties() {
    }

    public static BooleanPropertyType connection(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockPropertyTypes.MINECRAFT_CONNECTION_NORTH;
            case EAST -> BlockPropertyTypes.MINECRAFT_CONNECTION_EAST;
            case SOUTH -> BlockPropertyTypes.MINECRAFT_CONNECTION_SOUTH;
            case WEST -> BlockPropertyTypes.MINECRAFT_CONNECTION_WEST;
            case UP -> BlockPropertyTypes.MINECRAFT_CONNECTION_UP;
            case DOWN -> BlockPropertyTypes.MINECRAFT_CONNECTION_DOWN;
        };
    }

    public static EnumPropertyType<?> redstone(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockPropertyTypes.REDSTONE_NORTH;
            case EAST -> BlockPropertyTypes.REDSTONE_EAST;
            case SOUTH -> BlockPropertyTypes.REDSTONE_SOUTH;
            case WEST -> BlockPropertyTypes.REDSTONE_WEST;
            default -> throw new IllegalArgumentException("Redstone connections must be horizontal");
        };
    }
}
