package org.allaymc.server.container.processor;

import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.container.interfaces.InventoryContainer;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.player.Player;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.MineBlockAction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MineBlockDurabilityTest {
    @Test
    void predictionMismatchResendsTheAuthoritativeItemWithoutChangingItsDurability() {
        var player = mock(Player.class);
        var actor = mock(EntityPlayer.class);
        var inventory = mock(InventoryContainer.class);
        var item = mock(ItemStack.class);
        when(player.getControlledEntity()).thenReturn(actor);
        doReturn(inventory).when(actor).getContainer(ContainerTypes.INVENTORY);
        when(inventory.getItemInHand()).thenReturn(item);
        when(item.getUniqueId()).thenReturn(27);
        when(item.getDamage()).thenReturn(1);
        var action = new MineBlockAction(0, 2, 27);
        try (var access = mockStatic(ContainerActionProcessor.class)) {
            access.when(() -> ContainerActionProcessor.getSlotType(inventory, 0))
                    .thenReturn(org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType.HOTBAR);
            access.when(() -> ContainerActionProcessor.toNetworkSlotIndex(inventory, 0)).thenReturn(0);
            var response = new MineBlockActionProcessor().handle(action, player, 0,
                    new ItemStackRequestAction[]{action}, new HashMap<>());
            assertTrue(response.ok());
        }
        verify(inventory).sendContent(player, 0);
        verify(item, never()).setDamage(anyInt());
    }
}
