package org.allaymc.server.entity.component.projectile;

import org.allaymc.api.entity.component.EntityPotionComponent;
import org.allaymc.api.entity.effect.EffectInstance;
import org.allaymc.api.entity.interfaces.EntityLiving;
import org.allaymc.api.entity.interfaces.EntitySplashPotion;
import org.allaymc.api.item.data.PotionType;
import org.allaymc.api.math.location.Location3d;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.world.manager.EntityManager;
import org.allaymc.api.world.physics.EntityPhysicsEngine;
import org.allaymc.testutils.AllayTestExtension;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class EntityPotionPhysicsComponentImplTest {
    @Test
    void eachTargetGetsIndependentDurationRegardlessOfIterationOrder() {
        var fixture = new Fixture(PotionType.LONG_SWIFTNESS);
        fixture.component.splash(null, null);
        var first = effect(fixture.first);
        var second = effect(fixture.second);

        assertNotSame(first, second);
        assertEquals(4800, first.getDuration());
        assertEquals(7200, second.getDuration());
        first.setDuration(1);
        assertEquals(7200, second.getDuration());
    }

    @Test
    void instantHealingFallsOffWithDistance() {
        var fixture = new Fixture(PotionType.STRONG_HEALING);
        fixture.component.splash(null, null);

        assertEquals(0.5, effect(fixture.first).getIntensity());
        assertEquals(0.75, effect(fixture.second).getIntensity());
    }

    @Test
    void directHitGetsFullIntensity() {
        var fixture = new Fixture(PotionType.STRONG_HEALING);
        fixture.component.splash(null, fixture.first);

        assertEquals(1.0, effect(fixture.first).getIntensity());
    }

    @Test
    void targetsAtTheRadiusBoundaryReceiveNoEffect() {
        var fixture = new Fixture(PotionType.STRONG_HEALING);
        when(fixture.first.getLocation()).thenReturn(new Location3d(4, 0, 0, fixture.dimension));
        fixture.component.splash(null, null);

        verify(fixture.first, never()).addEffect(any());
        verify(fixture.second).addEffect(any());
    }

    private static EffectInstance effect(EntityLiving entity) {
        var capture = ArgumentCaptor.forClass(EffectInstance.class);
        verify(entity).addEffect(capture.capture());
        return capture.getValue();
    }

    private static final class Fixture {
        private final Dimension dimension = mock(Dimension.class);
        private final EntityLiving first = mock(EntityLiving.class);
        private final EntityLiving second = mock(EntityLiving.class);
        private final EntitySplashPotionPhysicsComponentImpl component = new EntitySplashPotionPhysicsComponentImpl();

        private Fixture(PotionType type) {
            var projectile = mock(EntitySplashPotion.class);
            var manager = mock(EntityManager.class);
            var physics = mock(EntityPhysicsEngine.class);
            when(projectile.getDimension()).thenReturn(dimension);
            when(projectile.getLocation()).thenReturn(new Location3d(0, 0, 0, dimension));
            when(projectile.getOffsetAABB()).thenReturn(new AABBd(0, 0, 0, 0.25, 0.25, 0.25));
            when(dimension.getEntityManager()).thenReturn(manager);
            when(manager.getPhysicsService()).thenReturn(physics);
            when(physics.computeCollidingEntities(any(AABBdc.class))).thenReturn(List.of(first, second));
            when(first.getLocation()).thenReturn(new Location3d(2, 0, 0, dimension));
            when(second.getLocation()).thenReturn(new Location3d(1, 0, 0, dimension));
            component.thisEntity = projectile;
            component.potionComponent = mock(EntityPotionComponent.class);
            when(component.potionComponent.getPotionType()).thenReturn(type);
        }
    }
}
