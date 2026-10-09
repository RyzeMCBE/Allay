package org.allaymc.api.entity.interfaces;

/**
 * Represents the vanilla NPC entity.
 * <p>
 * NPCs expose the normal living-entity state (including health) to the client,
 * but vanilla NPCs reject damage.
 */
public interface EntityNpc extends EntityLiving {
}
