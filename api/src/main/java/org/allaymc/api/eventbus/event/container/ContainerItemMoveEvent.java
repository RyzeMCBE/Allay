package org.allaymc.api.eventbus.event.container;

import lombok.Getter;
import org.allaymc.api.annotation.CallerThread;
import org.allaymc.api.annotation.ThreadType;
import org.allaymc.api.container.Container;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.eventbus.event.CancellableEvent;
import org.allaymc.api.item.type.ItemType;

/**
 * Called before an item is moved between container slots by a player or automation.
 *
 * @author daoge_cmd
 */
@Getter
@CallerThread(ThreadType.WORLD)
@CallerThread(ThreadType.DIMENSION)
public class ContainerItemMoveEvent extends ContainerEvent implements CancellableEvent {
    /**
     * The source container.
     */
    protected Container source;
    /**
     * The source slot index.
     */
    protected int sourceSlot;
    /**
     * The destination container.
     */
    protected Container destination;
    /**
     * The destination slot index.
     */
    protected int destinationSlot;
    /**
     * The type of item being moved.
     */
    protected ItemType<?> itemType;
    /**
     * The number of items being moved.
     */
    protected int count;
    /**
     * The player initiating the move, or {@code null} for server-side automation.
     */
    protected EntityPlayer player;

    /** Creates a server-side movement event without a player actor. */
    public ContainerItemMoveEvent(Container source, int sourceSlot, Container destination, int destinationSlot, ItemType<?> itemType, int count) {
        this(source, sourceSlot, destination, destinationSlot, itemType, count, null);
    }

    /**
     * Creates a movement event with its initiating player.
     *
     * @param player the player initiating the move, or {@code null} for automation
     */
    public ContainerItemMoveEvent(Container source, int sourceSlot, Container destination, int destinationSlot, ItemType<?> itemType, int count, EntityPlayer player) {
        this.source = source;
        this.sourceSlot = sourceSlot;
        this.destination = destination;
        this.destinationSlot = destinationSlot;
        this.itemType = itemType;
        this.count = count;
        this.player = player;
    }
}
