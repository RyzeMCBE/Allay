package org.allaymc.server.entity.component.player;

import org.allaymc.api.entity.component.EntityBaseComponent;
import org.allaymc.api.entity.effect.EffectInstance;
import org.allaymc.api.entity.effect.EffectTypes;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.math.location.Location3d;
import org.allaymc.api.player.Player;
import org.allaymc.api.world.Dimension;
import org.allaymc.server.item.component.edible.ItemEnchantedGoldenAppleEdibleComponentImpl;
import org.allaymc.server.item.component.edible.ItemGoldenAppleEdibleComponentImpl;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class GoldenAppleAbsorptionTest {
    private final ItemGoldenAppleEdibleComponentImpl normal = new ItemGoldenAppleEdibleComponentImpl();
    private final ItemEnchantedGoldenAppleEdibleComponentImpl enchanted = new ItemEnchantedGoldenAppleEdibleComponentImpl();

    @Test
    void repeatedNormalApplesRefillToFourWithoutAddingHearts() {
        var player = new TestPlayer();
        for (int i = 0; i < 4; i++) {
            normal.onEaten(player.entity);
            assertEquals(4, player.getAbsorption());
            player.setAbsorption(1);
        }
        normal.onEaten(player.entity);
        assertEquals(4, player.getAbsorption());
        assertEquals(1, player.getEffectLevel(EffectTypes.ABSORPTION));
    }

    @Test
    void enchantedAppleUpgradesExistingNormalAbsorption() {
        var player = new TestPlayer();
        normal.onEaten(player.entity);
        enchanted.onEaten(player.entity);
        assertEquals(16, player.getAbsorption());
        assertEquals(4, player.getEffectLevel(EffectTypes.ABSORPTION));
    }

    @Test
    void weakerAppleCannotDowngradeOrExtendTheStrongerEffect() {
        var player = new TestPlayer();
        enchanted.onEaten(player.entity);
        player.getEffects().get(EffectTypes.ABSORPTION).setDuration(1200);
        player.setAbsorption(10);
        normal.onEaten(player.entity);
        assertEquals(10, player.getAbsorption());
        assertEquals(4, player.getEffectLevel(EffectTypes.ABSORPTION));
        assertEquals(1200, player.getEffects().get(EffectTypes.ABSORPTION).getDuration());
        assertEquals(List.of("effect:4", "absorption:10.0"), player.lastAbsorptionUpdate());
    }

    @Test
    void authoritativeAttributeFollowsEveryAbsorptionEffectUpdateAndRemoval() {
        var player = new TestPlayer();
        normal.onEaten(player.entity);
        assertEquals(List.of("effect:1", "absorption:4.0"), player.lastAbsorptionUpdate());
        player.setAbsorption(0);
        normal.onEaten(player.entity);
        assertEquals(List.of("effect:1", "absorption:4.0"), player.lastAbsorptionUpdate());
        player.removeEffect(EffectTypes.ABSORPTION);
        assertEquals(0, player.getAbsorption());
        assertEquals(List.of("effect:remove", "absorption:0.0"), player.lastAbsorptionUpdate());
    }

    private static final class TestPlayer extends EntityPlayerLivingComponentImpl {
        private final EntityPlayer entity = mock(EntityPlayer.class);
        private final List<String> updates = new ArrayList<>();

        private TestPlayer() {
            thisPlayer = entity;
            thisEntity = entity;
            baseComponent = mock(EntityBaseComponent.class);
            var connection = mock(Player.class);
            var dimension = mock(Dimension.class);
            when(entity.isActualPlayer()).thenReturn(true);
            when(entity.getController()).thenReturn(connection);
            when(entity.getDimension()).thenReturn(dimension);
            when(entity.getLocation()).thenReturn(new Location3d(0, 64, 0, dimension));
            when(entity.addEffect(any(EffectInstance.class))).thenAnswer(call -> addEffect(call.getArgument(0)));
            doAnswer(call -> { setAbsorption(call.getArgument(0)); return null; })
                    .when(entity).setAbsorption(anyFloat());
            doAnswer(call -> { updates.add("absorption:" + call.getArgument(0)); return null; })
                    .when(connection).sendAbsorption(anyFloat());
            doAnswer(call -> {
                EffectInstance newer = call.getArgument(1);
                EffectInstance older = call.getArgument(2);
                var changed = newer != null ? newer : older;
                if (changed.getType() == EffectTypes.ABSORPTION) {
                    updates.add("effect:" + (newer == null ? "remove" : newer.getLevel()));
                }
                return null;
            }).when(connection).viewEntityEffectChange(eq(entity), nullable(EffectInstance.class), nullable(EffectInstance.class));
        }

        private List<String> lastAbsorptionUpdate() {
            return updates.subList(updates.size() - 2, updates.size());
        }
    }
}
