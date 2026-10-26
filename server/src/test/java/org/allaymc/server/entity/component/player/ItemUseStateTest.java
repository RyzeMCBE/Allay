package org.allaymc.server.entity.component.player;

import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.type.ItemType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ItemUseStateTest {
    @Test
    void replacementInTheSameSlotCancelsTheOriginalUse() {
        var player = mock(EntityPlayer.class);
        var component = mock(EntityPlayerBaseComponentImpl.class, CALLS_REAL_METHODS);
        component.thisPlayer = player;
        component.cooldowns = new HashMap<>();
        doNothing().when(component).broadcastState();
        var original = item(101);
        var replacement = item(202);
        when(player.getItemInHand()).thenReturn(original);
        when(player.getHandSlot()).thenReturn(0);
        component.setUsingItemInAir(true);
        assertTrue(component.isUsingItemInAir());
        when(player.getItemInHand()).thenReturn(replacement);
        assertFalse(component.isUsingItemInAir());
    }

    @Test
    void emptyHandAndSlotChangesInvalidateAnActiveUse() {
        var player = mock(EntityPlayer.class);
        var component = mock(EntityPlayerBaseComponentImpl.class, CALLS_REAL_METHODS);
        component.thisPlayer = player;
        component.cooldowns = new HashMap<>();
        doNothing().when(component).broadcastState();
        var held = item(101);
        when(player.getItemInHand()).thenReturn(held);
        component.setUsingItemInAir(true);
        when(player.getHandSlot()).thenReturn(1);
        assertFalse(component.isUsingItemInAir());
        component.setUsingItemInAir(true);
        when(held.isEmptyOrAir()).thenReturn(true);
        assertFalse(component.isUsingItemInAir());
    }

    private static ItemStack item(int networkId) {
        var item = mock(ItemStack.class);
        doReturn(mock(ItemType.class)).when(item).getItemType();
        when(item.getUniqueId()).thenReturn(networkId);
        return item;
    }
}
