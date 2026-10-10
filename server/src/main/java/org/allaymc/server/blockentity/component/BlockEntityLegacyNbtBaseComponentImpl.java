package org.allaymc.server.blockentity.component;

import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.cloudburstmc.nbt.NbtMap;

/**
 * Retains Bedrock/PocketMine block-entity tags that this Allay version does not
 * interpret. Fields such as MobSpawner's SpawnPotentials must survive a
 * load/save cycle even when their behavior is not implemented yet.
 */
public class BlockEntityLegacyNbtBaseComponentImpl extends BlockEntityBaseComponentImpl {

    private NbtMap originalNbt = NbtMap.EMPTY;

    public BlockEntityLegacyNbtBaseComponentImpl(BlockEntityInitInfo initInfo) {
        super(initInfo);
    }

    @Override
    public void loadNBT(NbtMap nbt) {
        super.loadNBT(nbt);
        this.originalNbt = nbt;
    }

    @Override
    public NbtMap saveNBT() {
        var state = super.saveNBT();
        var builder = originalNbt.toBuilder();
        builder.putAll(state);
        // Unknown types must keep their original storage identifier instead of
        // being rewritten as Allay's internal fallback registry name.
        if (originalNbt.containsKey("id")) {
            builder.putString("id", originalNbt.getString("id"));
        }
        return builder.build();
    }
}
