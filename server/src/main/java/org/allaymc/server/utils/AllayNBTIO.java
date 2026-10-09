package org.allaymc.server.utils;

import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.block.property.type.BlockPropertyType;
import org.allaymc.api.block.type.BlockState;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.blockentity.BlockEntity;
import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.allaymc.api.entity.Entity;
import org.allaymc.api.entity.EntityInitInfo;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.ItemStackInitInfo;
import org.allaymc.api.item.interfaces.ItemAirStack;
import org.allaymc.api.registry.Registries;
import org.allaymc.api.utils.NBTIO;
import org.allaymc.api.utils.identifier.Identifier;
import org.allaymc.api.world.Dimension;
import org.allaymc.server.network.ProtocolInfo;
import org.allaymc.updater.block.BlockStateUpdaters;
import org.allaymc.updater.item.ItemStateUpdaters;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.Objects;

/**
 * @author daoge_cmd
 */
@Slf4j
public class AllayNBTIO implements NBTIO {
    @Override
    public BlockState fromBlockStateNBT(NbtMap nbt) {
        // Older states must be migrated. Newer states are still worth resolving:
        // if their name/properties are already known, preserving compatibility is safe.
        var version = nbt.getInt("version", 0);
        if (version < ProtocolInfo.BLOCK_STATE_VERSION_NUM) {
            nbt = BlockStateUpdaters.updateBlockState(nbt, ProtocolInfo.BLOCK_STATE_UPDATER.getVersion());
        }

        // Get the block type. Malformed or future identifiers are treated like unknown blocks.
        var name = nbt.getString("name");
        final var blockType = getBlockType(name);
        if (blockType == null) {
            log.warn("Unknown block type {}", name);
            return BlockTypes.UNKNOWN.getDefaultState();
        }

        // Add missing properties
        var states = nbt.getCompound("states");
        var builder = states.toBuilder();
        for (var entry : blockType.getDefaultState().getPropertyValues().entrySet()) {
            if (builder.containsKey(entry.getKey().getName())) {
                continue;
            }

            builder.put(entry.getKey().getName(), entry.getValue().getSerializedValue());
        }
        states = builder.build();

        // Create the block property value list
        var blockPropertyValues = new ArrayList<BlockPropertyType.BlockPropertyValue<?, ?, ?>>();
        for (var entry : states.entrySet()) {
            var property = blockType.getProperties().get(entry.getKey());
            if (property == null) {
                log.warn("Unknown property {} for block {}", entry.getKey(), name);
                return BlockTypes.UNKNOWN.getDefaultState();
            }

            try {
                var value = property.tryCreateValue(entry.getValue());
                if (value == null) {
                    log.warn("Invalid value {} for block property {} on {}", entry.getValue(), entry.getKey(), name);
                    return BlockTypes.UNKNOWN.getDefaultState();
                }
                blockPropertyValues.add(value);
            } catch (IllegalArgumentException | ClassCastException exception) {
                log.warn("Invalid value {} for block property {} on {}", entry.getValue(), entry.getKey(), name);
                return BlockTypes.UNKNOWN.getDefaultState();
            }
        }

        // Get the block state
        final BlockState blockState;
        try {
            blockState = blockType.ofState(blockPropertyValues);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid block state {}", nbt);
            return BlockTypes.UNKNOWN.getDefaultState();
        }
        if (blockState == null) {
            log.warn("Invalid block state {}", nbt);
            return BlockTypes.UNKNOWN.getDefaultState();
        }

        return blockState;
    }

    @Override
    public ItemStack fromItemStackNBT(NbtMap nbt) {
        try {
            nbt = ItemStateUpdaters.updateItemState(nbt, ProtocolInfo.ITEM_STATE_UPDATER.getVersion());
            int count = nbt.getByte("Count", (byte) 1);
            int meta = nbt.getShort("Damage");
            var name = nbt.getString("Name");
            var itemType = Objects.requireNonNull(Registries.ITEMS.get(new Identifier(name)), "Unknown item type " + name + " while loading container items!");
            return itemType.createItemStack(
                    ItemStackInitInfo
                            .builder()
                            .count(count)
                            .meta(meta)
                            .extraTag(nbt.getCompound("tag"))
                            .build()
            );
        } catch (Throwable t) {
            log.error("Failed to load item from NBT", t);
            return ItemAirStack.AIR_STACK;
        }
    }

    @Override
    public Entity fromEntityNBT(Dimension dimension, NbtMap nbt) {
        final Identifier identifier;
        try {
            identifier = new Identifier(nbt.getString("identifier"));
        } catch (RuntimeException exception) {
            log.warn("Malformed entity identifier {}", nbt.getString("identifier"));
            return null;
        }

        var entityType = Registries.ENTITIES.get(identifier);
        if (entityType == null) {
            log.warn("Unknown entity type {}", identifier);
            return null;
        }

        return entityType.createEntity(EntityInitInfo.builder().dimension(dimension).nbt(nbt).build());
    }

    private org.allaymc.api.block.type.BlockType<?> getBlockType(String name) {
        try {
            return Registries.BLOCKS.get(new Identifier(name));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Override
    public BlockEntity fromBlockEntityNBT(Dimension dimension, NbtMap nbt) {
        var id = nbt.getString("id");
        var blockEntityType = Registries.BLOCK_ENTITIES.get(id);
        if (blockEntityType == null) {
            log.warn("Unknown block entity type: {}", id);
            return null;
        }

        return blockEntityType.createBlockEntity(BlockEntityInitInfo.builder().dimension(dimension).nbt(nbt).build());
    }
}
