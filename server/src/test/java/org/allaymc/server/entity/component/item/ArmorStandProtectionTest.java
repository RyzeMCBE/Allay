package org.allaymc.server.entity.component.item;

import org.allaymc.api.entity.EntityInitInfo;
import org.allaymc.api.entity.Entity;
import org.allaymc.api.entity.damage.DamageContainer;
import org.allaymc.api.entity.type.EntityTypes;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.api.eventbus.event.entity.EntityDamageEvent;
import org.allaymc.api.server.Server;
import org.allaymc.api.world.Dimension;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
public class ArmorStandProtectionTest {
    public static class ProtectionListener {
        private final Entity target;
        public ProtectionListener(Entity target) { this.target = target; }
        @EventHandler public void protect(EntityDamageEvent event) {
            if (event.getEntity()==target) event.setCancelled(true);
        }
    }
    @Test
    void cancelledDamageCannotTriggerBreakOrEquipmentDrops() {
        var dimension = mock(Dimension.class);
        var stand = EntityTypes.ARMOR_STAND.createEntity(EntityInitInfo.builder().dimension(dimension).build());
        var listener = new ProtectionListener(stand);
        var bus = Server.getInstance().getEventBus();
        bus.registerListener(listener);
        try {
            clearInvocations(dimension);
            assertFalse(stand.attack(DamageContainer.api(1)));
            assertFalse(stand.attack(DamageContainer.api(1)));
            verifyNoInteractions(dimension);
        } finally {
            bus.unregisterListener(listener);
        }
    }
}
