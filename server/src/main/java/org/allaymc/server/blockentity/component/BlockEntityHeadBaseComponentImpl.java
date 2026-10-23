package org.allaymc.server.blockentity.component;

import lombok.Getter;
import org.allaymc.api.block.data.CompassRoseDirection;
import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.allaymc.api.blockentity.component.BlockEntityHeadBaseComponent;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.server.block.component.event.CBlockOnPlaceEvent;
import org.cloudburstmc.nbt.NbtMap;

import java.util.Objects;

/**
 * @author daoge_cmd
 */
@Getter
public class BlockEntityHeadBaseComponentImpl extends BlockEntityBaseComponentImpl implements BlockEntityHeadBaseComponent {

    protected static final String TAG_ROTATION = "Rotation";
    protected static final String TAG_DOING_ANIMATION = "DoingAnimation";
    protected static final String TAG_MOUTH_TICK_COUNT = "MouthTickCount";

    protected CompassRoseDirection direction = CompassRoseDirection.SOUTH;
    protected boolean playingAnimation;
    protected int animationTicks;

    public BlockEntityHeadBaseComponentImpl(BlockEntityInitInfo initInfo) {
        super(initInfo);
    }

    @Override
    public void tick(long currentTick) {
        super.tick(currentTick);
        if (this.playingAnimation) {
            this.animationTicks++;
            sendBlockEntityToViewers();
        }
    }

    @Override
    public void setDirection(CompassRoseDirection direction) {
        this.direction = Objects.requireNonNull(direction, "direction");
        sendBlockEntityToViewers();
    }

    @Override
    public void setPlayingAnimation(boolean playingAnimation) {
        this.playingAnimation = playingAnimation;
        sendBlockEntityToViewers();
    }

    @Override
    public void setAnimationTicks(int ticks) {
        this.animationTicks = ticks;
        sendBlockEntityToViewers();
    }

    @Override
    public void loadNBT(NbtMap nbt) {
        super.loadNBT(nbt);
        direction = CompassRoseDirection.SOUTH;
        if (nbt.get(TAG_ROTATION) instanceof Number rotation && Double.isFinite(rotation.doubleValue())) {
            direction = CompassRoseDirection.getClosestFromYaw(rotation.doubleValue());
        } else if (nbt.get("Rot") instanceof Number rotation && rotation.intValue() >= 0 && rotation.intValue() < 16) {
            // Imported PocketMine heads store the sixteen-way rotation as a byte index.
            direction = CompassRoseDirection.from(rotation.intValue());
        }
        nbt.listenForBoolean(TAG_DOING_ANIMATION, value -> this.playingAnimation = value);
        nbt.listenForInt(TAG_MOUTH_TICK_COUNT, value -> this.animationTicks = value);
    }

    @Override
    public NbtMap saveNBT() {
        return super.saveNBT()
                .toBuilder()
                .putFloat(TAG_ROTATION, Objects.requireNonNullElse(this.direction, CompassRoseDirection.SOUTH).getYaw())
                .putBoolean(TAG_DOING_ANIMATION, this.playingAnimation)
                .putInt(TAG_MOUTH_TICK_COUNT, this.animationTicks)
                .build();
    }

    @EventHandler
    protected void onBlockPlace(CBlockOnPlaceEvent event) {
        var placementInfo = event.getPlacementInfo();
        if (placementInfo != null) {
            this.direction = CompassRoseDirection.getClosestFromYaw(placementInfo.player().getLocation().yaw());
        }
    }
}
