package org.allaymc.server.entity.component;

import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.container.interfaces.ArmorContainer;
import org.allaymc.api.container.interfaces.InventoryContainer;
import org.allaymc.api.entity.component.EntityContainerHolderComponent;
import org.allaymc.api.entity.damage.DamageContainer;
import org.allaymc.api.entity.damage.DamageType;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.data.ItemData;
import org.allaymc.api.item.enchantment.EnchantmentTypes;
import org.allaymc.api.item.type.ItemType;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class CombatDamageCalculationTest {
    @Test
    void sharpnessThreeAgainstFullProtectionThreeUsesDiamondToughness() {
        var component = new TestLiving(2);
        var damage = DamageContainer.entityAttack(attacker(false), 7);
        component.applyAttacker(damage);
        assertEquals(10.75F, damage.getFinalDamage());
        component.applyArmor(damage);
        assertEquals(1.718925F, damage.getFinalDamage(), 0.00001F);
    }

    @Test
    void netheriteToughnessReducesMoreDamageThanDiamond() {
        var component = new TestLiving(3);
        var damage = DamageContainer.entityAttack(attacker(false), 7);
        component.applyAttacker(damage);
        component.applyArmor(damage);
        assertEquals(1.5987397F, damage.getFinalDamage(), 0.00001F);
    }

    @Test
    void criticalIsAppliedOnceBeforeArmorAndProtection() {
        var component = new TestLiving(2);
        var damage = DamageContainer.entityAttack(attacker(true), 7);
        component.applyAttacker(damage);
        assertEquals(16.125F, damage.getFinalDamage());
        component.applyArmor(damage);
        assertEquals(3.029081F, damage.getFinalDamage(), 0.00001F);
    }

    @Test
    void explicitCriticalDecisionSurvivesNativeEligibility() {
        var component = new TestLiving(2);
        var disabled = DamageContainer.entityAttack(attacker(true), 7);
        disabled.setCritical(false);
        component.applyAttacker(disabled);
        assertFalse(disabled.isCritical());
        assertEquals(10.75F, disabled.getFinalDamage());

        var enabled = DamageContainer.entityAttack(attacker(false), 7);
        enabled.setCritical(true);
        component.applyAttacker(enabled);
        assertTrue(enabled.isCritical());
        assertEquals(16.125F, enabled.getFinalDamage());
    }

    @Test
    void absorptionUsesItsPreviousValueAndOnlyTheRemainderHitsHealth() {
        for (float incoming : new float[]{2, 3, 4, 5}) {
            var component = new TestLiving(2);
            component.setAbsorption(4);
            var damage = DamageContainer.simpleAttack(incoming);
            component.applyEffects(damage);
            assertEquals(Math.max(0, incoming - 4), damage.getFinalDamage());
            assertEquals(Math.max(0, 4 - incoming), component.getAbsorption());
        }
    }

    private static EntityPlayer attacker(boolean critical) {
        var attacker = mock(EntityPlayer.class);
        var inventory = mock(InventoryContainer.class);
        var sword = mock(ItemStack.class);
        when(attacker.hasContainer(ContainerTypes.INVENTORY)).thenReturn(true);
        when(attacker.getContainer(ContainerTypes.INVENTORY)).thenReturn(inventory);
        when(inventory.getItemInHand()).thenReturn(sword);
        when(sword.getEnchantmentLevel(EnchantmentTypes.SHARPNESS)).thenReturn(3);
        when(attacker.canCriticalAttack()).thenReturn(critical);
        return attacker;
    }

    private static ItemStack armorPiece(int value, int toughness) {
        var item = mock(ItemStack.class);
        var type = mock(ItemType.class);
        doReturn(type).when(item).getItemType();
        when(type.getItemData()).thenReturn(ItemData.builder().armorValue(value).toughnessValue(toughness).build());
        when(item.getEnchantmentProtectionFactor(DamageType.ENTITY_ATTACK)).thenReturn(3);
        return item;
    }

    private static final class TestLiving extends EntityLivingComponentImpl {
        private TestLiving(int toughness) {
            containerHolderComponent = mock(EntityContainerHolderComponent.class);
            var armor = mock(ArmorContainer.class);
            when(containerHolderComponent.getContainer(ContainerTypes.ARMOR)).thenReturn(armor);
            var items = new ItemStack[]{armorPiece(3, toughness), armorPiece(8, toughness),
                    armorPiece(6, toughness), armorPiece(3, toughness)};
            when(armor.getItemStackArray()).thenReturn(items);
            when(armor.getItemStacks()).thenReturn(Arrays.asList(items));
        }
    }
}
