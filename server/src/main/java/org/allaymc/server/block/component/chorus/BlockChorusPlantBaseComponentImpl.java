package org.allaymc.server.block.component.chorus;

import org.allaymc.api.block.BlockBehavior;
import org.allaymc.api.block.data.BlockFace;
import org.allaymc.api.block.dto.Block;
import org.allaymc.api.block.type.BlockState;
import org.allaymc.api.block.type.BlockType;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.entity.Entity;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.type.ItemTypes;
import org.allaymc.api.math.position.Position3i;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.block.dto.PlayerInteractInfo;
import org.allaymc.server.block.BlockConnectionProperties;
import org.allaymc.server.block.BlockConnectionResolver;
import org.joml.Vector3ic;
import org.allaymc.server.block.component.BlockBaseComponentImpl;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author IWareQ
 */
public class BlockChorusPlantBaseComponentImpl extends BlockBaseComponentImpl implements BlockConnectionResolver {
    public BlockChorusPlantBaseComponentImpl(BlockType<? extends BlockBehavior> blockType) {
        super(blockType);
    }

    @Override
    public void onNeighborUpdate(Block block, Block neighbor, BlockFace face, BlockState oldNeighborState) {
        super.onNeighborUpdate(block, neighbor, face, oldNeighborState);
        if (!canBeSupportedAt(block)) {
            block.breakBlock();
        } else {
            var resolved = resolveConnections(block);
            if (!resolved.equals(block.getBlockState())) {
                block.replaceState(resolved);
            }
        }
    }

    @Override
    public boolean place(Dimension dimension, BlockState blockState, Vector3ic pos, PlayerInteractInfo info) {
        var connected = resolveConnections(new Block(blockState, new Position3i(pos, dimension)));
        return super.place(dimension, connected, pos, info);
    }

    @Override
    public void afterPlaced(Block block, BlockState state, PlayerInteractInfo info) {
        super.afterPlaced(block, state, info);
        var resolved = resolveConnections(new Block(state, block.getPosition()));
        if (!resolved.equals(state)) {
            var pos = block.getPosition();
            block.getDimension().setBlockState(pos.x(), pos.y(), pos.z(), resolved,
                    block.getLayer(), true, true, false, null);
        }
    }

    @Override
    public BlockState resolveConnections(Block block) {
        var state = block.getBlockState();
        for (var face : BlockFace.VALUES) {
            var neighbor = block.offsetPos(face).getBlockType();
            var connected = neighbor == BlockTypes.CHORUS_PLANT || neighbor == BlockTypes.CHORUS_FLOWER ||
                    (face == BlockFace.DOWN && neighbor == BlockTypes.END_STONE);
            state = state.setPropertyValue(BlockConnectionProperties.connection(face), connected);
        }
        return state;
    }

    private boolean canBeSupportedAt(Block current) {
        boolean sideSupport = false;
        for (var face : BlockFace.getHorizontalBlockFaces()) {
            var neighbor = current.offsetPos(face);
            if (neighbor.getBlockType() == BlockTypes.CHORUS_PLANT && hasDownSupport(neighbor)) {
                sideSupport = true;
                break;
            }
        }

        if (sideSupport) {
            var upperBlocked = !current.offsetPos(BlockFace.UP).isAir();
            var downBlocked = !current.offsetPos(BlockFace.DOWN).isAir();
            return !(upperBlocked && downBlocked);
        } else {
            return hasDownSupport(current);
        }
    }

    private boolean hasDownSupport(Block current) {
        var downType = current.offsetPos(BlockFace.DOWN).getBlockType();
        return downType == BlockTypes.CHORUS_PLANT || downType == BlockTypes.END_STONE;
    }

    @Override
    public Set<ItemStack> getDrops(Block block, ItemStack usedItem, Entity entity) {
        if (ThreadLocalRandom.current().nextBoolean()) {
            return Set.of(ItemTypes.CHORUS_FRUIT.createItemStack());
        }

        return Set.of();
    }

    @Override
    public ItemStack getSilkTouchDrop(Block block) {
        return ItemTypes.AIR.createItemStack();
    }
}
