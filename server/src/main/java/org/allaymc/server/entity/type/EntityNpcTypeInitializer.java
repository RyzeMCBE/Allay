package org.allaymc.server.entity.type;

import org.allaymc.api.entity.damage.DamageContainer;
import org.allaymc.api.entity.type.EntityTypes;
import org.allaymc.server.entity.component.EntityLivingComponentImpl;
import org.allaymc.server.entity.data.EntityId;
import org.allaymc.server.entity.impl.EntityNpcImpl;

/**
 * Initializes the vanilla NPC with living-entity state while preserving its
 * vanilla invulnerability. Bedrock clients expect NPCs to expose living state;
 * omitting it can leave client-side attack prediction unsynchronized.
 */
public final class EntityNpcTypeInitializer {

    private EntityNpcTypeInitializer() {
    }

    public static void init() {
        EntityTypes.NPC = AllayEntityType
                .builder(EntityNpcImpl.class)
                .vanillaEntity(EntityId.NPC)
                .addComponent(() -> new EntityLivingComponentImpl() {
                    @Override
                    public boolean canBeAttacked(DamageContainer damage) {
                        return false;
                    }

                    @Override
                    public boolean isFireproof() {
                        return true;
                    }
                }, EntityLivingComponentImpl.class)
                .build();
    }
}
