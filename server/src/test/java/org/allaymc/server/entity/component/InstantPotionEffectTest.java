package org.allaymc.server.entity.component;

import org.allaymc.api.entity.component.EntityBaseComponent;
import org.allaymc.api.entity.effect.EffectInstance;
import org.allaymc.api.entity.effect.EffectTypes;
import org.allaymc.api.entity.interfaces.EntityLiving;
import org.allaymc.api.eventbus.event.entity.EntityEffectAddEvent;
import org.allaymc.api.server.Server;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class InstantPotionEffectTest {
    @Test
    void twoHealingPotionsInTheSameTickBothHealWithoutLeavingAnActiveEffect() {
        var entity = mock(EntityLiving.class);
        var component = new TestLiving(entity);

        assertTrue(component.addEffect(EffectTypes.INSTANT_HEALTH.createInstance(1, 1)));
        assertTrue(component.addEffect(EffectTypes.INSTANT_HEALTH.createInstance(1, 1)));

        assertEquals(18.0F, component.getHealth());
        assertFalse(component.hasEffect(EffectTypes.INSTANT_HEALTH));
    }

    @Test
    void healingUsesSplashIntensityAndCopiesPreserveIt() {
        var entity = mock(EntityLiving.class);
        var component = new TestLiving(entity);
        var effect = EffectTypes.INSTANT_HEALTH.createInstance(1, 1);
        effect.setIntensity(0.5);

        assertTrue(component.addEffect(new EffectInstance(effect)));

        assertEquals(6.0F, component.getHealth());
        assertThrows(IllegalArgumentException.class, () -> effect.setIntensity(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> effect.setIntensity(-0.1));
        assertThrows(IllegalArgumentException.class, () -> effect.setIntensity(1.1));
    }

    @Test
    void negligibleInstantDamageDoesNotStartADamageCooldown() {
        var entity = mock(EntityLiving.class);
        var effect = EffectTypes.INSTANT_DAMAGE.createInstance(1, 1);
        effect.setIntensity(0.01);
        EffectTypes.INSTANT_DAMAGE.onAdd(entity, effect);
        verify(entity, never()).attack(any(org.allaymc.api.entity.damage.DamageContainer.class));
    }

    @Test
    void cancelledInstantEffectDoesNotHeal() {
        var entity = mock(EntityLiving.class);
        var component = new TestLiving(entity);
        Consumer<EntityEffectAddEvent> cancel = event -> {
            if (event.getEntity() == entity) event.setCancelled(true);
        };
        var bus = Server.getInstance().getEventBus();
        bus.registerListenerFor(EntityEffectAddEvent.class, cancel);
        try {
            assertFalse(component.addEffect(EffectTypes.INSTANT_HEALTH.createInstance(1, 1)));
            assertEquals(2.0F, component.getHealth());
        } finally {
            bus.unregisterListenerFor(EntityEffectAddEvent.class, cancel);
        }
    }

    private static final class TestLiving extends EntityLivingComponentImpl {
        private TestLiving(EntityLiving entity) {
            this.thisEntity = entity;
            this.baseComponent = mock(EntityBaseComponent.class);
            this.health = 2.0F;
            when(entity.getHealth()).thenAnswer(call -> this.health);
            doAnswer(call -> {
                setHealth(call.getArgument(0));
                return null;
            }).when(entity).setHealth(anyFloat());
        }
    }
}
