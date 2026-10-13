package org.allaymc.api.eventbus.event.player;

import org.allaymc.api.annotation.CallerThread;
import org.allaymc.api.annotation.ThreadType;
import org.allaymc.api.container.Container;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.eventbus.event.CancellableEvent;

import java.util.List;

/**
 * Called before any action in a client's item-stack request is executed.
 * Cancellation rejects the entire request without running its actions.
 * This is a read-only policy hook; listeners must not mutate the referenced containers.
 * It does not cover server-side inventory changes or item use outside item-stack requests.
 */
@CallerThread(ThreadType.DIMENSION)
public final class PlayerInventoryRequestEvent extends PlayerEvent implements CancellableEvent {
    private final List<Slot> slots;

    /** Creates a request guard with the explicitly referenced native container slots. */
    public PlayerInventoryRequestEvent(EntityPlayer player, List<Slot> slots) {
        super(player);
        this.slots = List.copyOf(slots);
    }

    /**
     * Returns both endpoints of transfers/swaps and sources of drops, consumption and destruction.
     * Duplicate references are retained. Implicit recipe output slots are not included.
     * Items observed here are the server state before the first action, not client predictions.
     */
    public List<Slot> getSlots() {
        return slots;
    }

    /** A native container and its validated, zero-based slot index. */
    public record Slot(Container container, int index) {
        /** Rejects missing containers and indices outside the current container. */
        public Slot {
            if (container == null || index < 0 || index >= container.getItemStackArray().length) {
                throw new IllegalArgumentException("Invalid inventory request slot");
            }
        }
    }
}
