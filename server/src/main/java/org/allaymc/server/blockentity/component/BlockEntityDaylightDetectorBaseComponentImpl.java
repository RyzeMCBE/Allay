package org.allaymc.server.blockentity.component;

import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.cloudburstmc.nbt.NbtMap;

/** Preserves native detector tiles and resumes the block's light updates after loading. */
public final class BlockEntityDaylightDetectorBaseComponentImpl extends BlockEntityLegacyNbtBaseComponentImpl {
    private boolean initialized;

    public BlockEntityDaylightDetectorBaseComponentImpl(BlockEntityInitInfo info) {
        super(info);
    }

    @Override
    public void loadNBT(NbtMap nbt) {
        super.loadNBT(nbt);
        initialized = false;
    }

    @Override
    public void tick(long currentTick) {
        if (initialized) return;
        initialized = true;
        var dimension = position.dimension();
        var type = dimension.getBlockState(position).getBlockType();
        if (type != BlockTypes.DAYLIGHT_DETECTOR && type != BlockTypes.DAYLIGHT_DETECTOR_INVERTED) return;
        var updates = dimension.getBlockUpdateManager();
        if (!updates.hasScheduledBlockUpdate(position)) {
            updates.scheduleBlockUpdateInDelay(position, 1L);
        }
    }
}
