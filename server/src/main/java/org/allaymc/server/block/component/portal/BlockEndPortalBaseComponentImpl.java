package org.allaymc.server.block.component.portal;

import org.allaymc.api.block.BlockBehavior;
import org.allaymc.api.block.data.BlockFace;
import org.allaymc.api.block.dto.Block;
import org.allaymc.api.block.type.BlockState;
import org.allaymc.api.block.type.BlockType;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.entity.Entity;
import org.allaymc.api.entity.component.EntityBaseComponent;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.eventbus.event.entity.EntityPortalEnterEvent;
import org.allaymc.api.eventbus.event.entity.EntityTeleportEvent;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.math.location.Location3d;
import org.joml.Vector3i;
import org.allaymc.api.server.Server;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.world.dimension.DimensionTypes;
import org.allaymc.server.block.component.BlockBaseComponentImpl;
import org.allaymc.server.block.EndArrivalHelper;

import java.util.Set;

import static org.allaymc.api.block.property.type.BlockPropertyTypes.END_PORTAL_EYE_BIT;

/**
 * Block component for end portal blocks.
 *
 * <p>Teleports entities to The End (from overworld) or back to the overworld spawn (from The End).
 * When entering The End, a 5×5 obsidian platform is created at the spawn point.
 *
 * @author daoge_cmd
 */
public class BlockEndPortalBaseComponentImpl extends BlockBaseComponentImpl {

    public BlockEndPortalBaseComponentImpl(BlockType<? extends BlockBehavior> blockType) {
        super(blockType);
    }

    @Override
    public void onNeighborUpdate(Block block, Block neighbor, BlockFace face, BlockState oldNeighborState) {
        super.onNeighborUpdate(block, neighbor, face, oldNeighborState);
        // End portal blocks are removed when a surrounding frame block is broken
        if (face.isHorizontal() && !isValidEndPortalNeighbor(neighbor)) {
            block.getDimension().setBlockState(block.getPosition(), BlockTypes.AIR.getDefaultState());
        }
    }

    private static boolean isValidEndPortalNeighbor(Block neighbor) {
        var type = neighbor.getBlockType();
        if (type == BlockTypes.END_PORTAL) return true;
        return type == BlockTypes.END_PORTAL_FRAME && neighbor.getPropertyValue(END_PORTAL_EYE_BIT);
    }

    @Override
    public void onCollideWithEntity(Block block, Entity entity) {
        if (entity.getPortalCooldown() > 0) {
            return;
        }

        var portalEvent = new EntityPortalEnterEvent(entity, EntityPortalEnterEvent.PortalType.END);
        if (!portalEvent.call()) {
            return;
        }
        // Capture the plugin's requested arrival point before async loading.
        var configuredArrival = portalEvent.getDestinationOverride() == null
                ? null : new Location3d(portalEvent.getDestinationOverride());

        // Set cooldown immediately to prevent re-entry while async teleport is in progress
        entity.setPortalCooldown(EntityBaseComponent.PORTAL_COOLDOWN_TICKS);

        var world = entity.getWorld();
        var currentDimType = entity.getDimension().getDimensionType();

        if (currentDimType != DimensionTypes.THE_END) {
            var theEnd = world.getTheEnd();
            if (theEnd == null) {
                entity.setPortalCooldown(0);
                return;
            }
            org.joml.Vector3ic preferred;
            try {
                if (configuredArrival != null && !validArrival(configuredArrival, theEnd)) {
                    entity.setPortalCooldown(0);
                    return;
                }
                preferred = configuredArrival == null
                        ? EndArrivalHelper.preferredSpawn(world, theEnd)
                        : new Vector3i((int) Math.floor(configuredArrival.x()),
                                       (int) Math.floor(configuredArrival.y()),
                                       (int) Math.floor(configuredArrival.z()));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(BlockEndPortalBaseComponentImpl.class)
                        .error("Could not read custom End spawn metadata; refusing unsafe teleport", e);
                entity.setPortalCooldown(0);
                return;
            }

            // Do not announce a speculative End landing point: the safe-arrival
            // search can select another floor location in custom worlds.
            // teleportOverDimension() will send the final event-adjusted target.
            // Chunk loading can block; keep it off the server tick thread.
            Server.getInstance().getVirtualThreadPool().submit(() -> {
                try {
                    int radius = EndArrivalHelper.searchRadius() + 2;
                    for (int cx = (preferred.x() - radius) >> 4;
                         cx <= (preferred.x() + radius) >> 4; cx++) {
                        for (int cz = (preferred.z() - radius) >> 4;
                             cz <= (preferred.z() + radius) >> 4; cz++) {
                            theEnd.getChunkManager().getOrLoadChunk(cx, cz).join();
                        }
                    }

                    // Position checks and block changes occur on the End scheduler.
                    theEnd.getScheduler().runLater(entity, () -> {
                        var arrival = EndArrivalHelper.findOrCreateArrival(theEnd, preferred);
                        entity.getScheduler().runLater(entity, () -> {
                            if (arrival.isEmpty() ||
                                    !entity.teleport(arrival.orElseThrow(), EntityTeleportEvent.Reason.END_PORTAL)) {
                                entity.setPortalCooldown(0);
                            }
                        });
                    });
                } catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(BlockEndPortalBaseComponentImpl.class)
                            .error("Cannot load custom End spawn chunks", e);
                    entity.getScheduler().runLater(entity, () -> {
                        entity.setPortalCooldown(0);
                    });
                }
            });
        } else {
            // By default, return to the original world spawn. Custom HCF
            // destinations first load their target chunk without blocking a tick.
            var overworld = world.getOverWorld();
            if (configuredArrival == null) {
                if (!entity.teleport(world.getSpawnPoint(), EntityTeleportEvent.Reason.END_PORTAL)) {
                    entity.setPortalCooldown(0);
                }
            } else if (!validArrival(configuredArrival, overworld)) {
                entity.setPortalCooldown(0);
            } else {
                Server.getInstance().getVirtualThreadPool().submit(() -> {
                    try {
                        int cx = ((int) Math.floor(configuredArrival.x())) >> 4;
                        int cz = ((int) Math.floor(configuredArrival.z())) >> 4;
                        overworld.getChunkManager().getOrLoadChunk(cx, cz).join();
                        // Do not teleport into solid blocks in custom spawn
                        // terrain. Read blocks on the target dimension thread,
                        // then perform the teleport on the entity's own thread.
                        overworld.getScheduler().runLater(entity, () -> {
                            boolean safe = EndArrivalHelper.isSafeConfiguredArrival(overworld, configuredArrival);
                            entity.getScheduler().runLater(entity, () -> {
                                if (!safe || !entity.teleport(configuredArrival, EntityTeleportEvent.Reason.END_PORTAL)) {
                                    entity.setPortalCooldown(0);
                                }
                            });
                        });
                    } catch (RuntimeException exception) {
                        org.slf4j.LoggerFactory.getLogger(BlockEndPortalBaseComponentImpl.class)
                                .error("Could not load custom End exit destination", exception);
                        entity.getScheduler().runLater(entity, () -> entity.setPortalCooldown(0));
                    }
                });
            }
        }
    }

    /**
     * Prevent invalid plugin coordinates from causing unsafe cross-world transfers
     * or integer overflows during chunk lookup.
     */
    private static boolean validArrival(Location3d location, Dimension expected) {
        return expected != null && location.dimension() == expected
                && Double.isFinite(location.x()) && Double.isFinite(location.y())
                && Double.isFinite(location.z())
                && Math.abs(location.x()) <= 29_999_984
                && Math.abs(location.z()) <= 29_999_984
                && location.y() >= expected.getDimensionType().getMinHeight() + 1
                && location.y() <= expected.getDimensionType().getMaxHeight() - 2;
    }

    /**
     * Compatibility entrypoint used by existing callers. Unlike the former
     * implementation, this never clears custom builds or overwrites terrain.
     * Call it only after the surrounding End chunks have been loaded.
     */
    public static void createEndPlatform(Dimension theEnd) {
        EndArrivalHelper.findOrCreateArrival(theEnd,
                EndArrivalHelper.preferredSpawn(theEnd.getWorld(), theEnd));
    }

    @Override
    public Set<ItemStack> getDrops(Block block, ItemStack usedItem, Entity entity) {
        return Set.of();
    }
}
