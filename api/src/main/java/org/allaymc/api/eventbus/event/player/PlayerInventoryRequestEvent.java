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
    private final List<Action> actions;

    /** Creates a request guard with the explicitly referenced native container slots. */
    public PlayerInventoryRequestEvent(EntityPlayer player, List<Slot> slots) {
        this(player, slots, List.of());
    }

    /** Creates a read-only guard containing each requested action in client order. */
    public PlayerInventoryRequestEvent(EntityPlayer player, List<Slot> slots, List<Action> actions) {
        super(player);
        this.slots = List.copyOf(slots);
        this.actions = List.copyOf(actions);
    }

    /**
     * Returns untrusted client intent before native validation or execution.
     * An empty list indicates a legacy event constructor. OTHER entries preserve unsupported
     * projection boundaries, such as recipes; they must not be interpreted as item transfers.
     * Plugins replacing a cancelled request must validate the entire ordered batch themselves.
     */
    public List<Action> getActions() {
        return actions;
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
    /** Describes an action without exposing protocol implementation types. */
    public enum Kind {
        /** Moves count items from source to destination. */
        TRANSFER,
        /** Exchanges both complete stacks; count is zero. */
        SWAP,
        /** Drops count items from source into the world. */
        DROP,
        /** Destroys count items from source. */
        DESTROY,
        /** Consumes count items from source, usually as part of a recipe. */
        CONSUME,
        /** Indicates an action whose effects are not represented by explicit slot transfers. */
        OTHER
    }

    /**
     * Describes client intent. Source is null only for OTHER; destination is present only for
     * TRANSFER and SWAP. Stack IDs are untrusted client values, including negative references
     * to an earlier action in the same request. This record does not grant mutation authority.
     */
    public record Action(Kind kind, int count, Slot source, Slot destination,
                         int sourceStackId, int destinationStackId) {
        /** Rejects inconsistent action shapes; native processors still validate item semantics. */
        public Action {
            java.util.Objects.requireNonNull(kind);
            if (kind == Kind.OTHER) {
                if (source != null || destination != null || count != 0) throw new IllegalArgumentException("Invalid opaque action");
            } else {
                java.util.Objects.requireNonNull(source);
                if ((kind == Kind.TRANSFER || kind == Kind.SWAP) != (destination != null)) throw new IllegalArgumentException("Invalid destination");
                if (kind == Kind.SWAP ? count != 0 : count <= 0) throw new IllegalArgumentException("Invalid item count");
            }
        }
    }

}
