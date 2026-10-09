package org.allaymc.server.entity.type;

import org.allaymc.api.entity.EntityInitInfo;
import org.allaymc.api.entity.component.EntityLivingComponent;
import org.allaymc.api.entity.damage.DamageContainer;
import org.allaymc.api.entity.interfaces.EntityNpc;
import org.allaymc.api.entity.type.EntityTypes;
import org.allaymc.api.world.Dimension;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(AllayTestExtension.class)
class EntityNpcTypeTest {
    static EntityNpc npc;
    static Dimension mockDimension = Mockito.mock(Dimension.class);

    @BeforeAll
    static void init() {
        npc = EntityTypes.NPC.createEntity(EntityInitInfo.builder()
                .pos(0, 1, 2)
                .dimension(mockDimension)
                .build());
    }

    @Test
    void testNpcUsesLivingStateButRejectsDamage() {
        assertInstanceOf(EntityLivingComponent.class, npc);
        assertFalse(npc.canBeAttacked(DamageContainer.simpleAttack(1)));
        assertTrue(npc.isFireproof());
    }
}
