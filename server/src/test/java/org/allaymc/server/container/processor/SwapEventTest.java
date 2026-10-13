package org.allaymc.server.container.processor;

import org.allaymc.api.container.Container;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.api.eventbus.event.container.ContainerItemMoveEvent;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.type.ItemType;
import org.allaymc.api.player.Player;
import org.allaymc.api.server.Server;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
public class SwapEventTest {
    public static final class Veto {
        final Container deniedSource;
        int calls;
        Veto(Container deniedSource) { this.deniedSource = deniedSource; }
        @EventHandler public void move(ContainerItemMoveEvent event) {
            calls++;
            if (event.getSource() == deniedSource) event.cancel();
        }
    }
    @Test void sourceVetoLeavesBothSlotsUntouched() { verifyVeto(false); }
    @Test void reverseVetoLeavesBothSlotsUntouched() { verifyVeto(true); }
    private void verifyVeto(boolean reverse) {
        var source = mock(Container.class);
        var destination = mock(Container.class);
        var sourceItem = mock(ItemStack.class);
        var destinationItem = mock(ItemStack.class);
        when(sourceItem.getItemType()).thenReturn(mock(ItemType.class));
        when(destinationItem.getItemType()).thenReturn(mock(ItemType.class));
        when(sourceItem.getCount()).thenReturn(1);
        when(destinationItem.getCount()).thenReturn(1);
        when(source.getItemStack(0)).thenReturn(sourceItem);
        when(destination.getItemStack(0)).thenReturn(destinationItem);
        var player = mock(Player.class);
        var action = mock(SwapAction.class, RETURNS_DEEP_STUBS);
        var from = action.source().containerName();
        var to = action.destination().containerName();
        var veto = new Veto(reverse ? destination : source);
        Server.getInstance().getEventBus().registerListener(veto);
        try (var access = mockStatic(ContainerActionProcessor.class)) {
            access.when(() -> ContainerActionProcessor.getContainerFrom(player, from)).thenReturn(source);
            access.when(() -> ContainerActionProcessor.getContainerFrom(player, to)).thenReturn(destination);
            access.when(() -> ContainerActionProcessor.canPlaceItemToSlot(destination, 0, sourceItem)).thenReturn(true);
            access.when(() -> ContainerActionProcessor.canPlaceItemToSlot(source, 0, destinationItem)).thenReturn(true);
            assertFalse(new SwapActionProcessor().handle(action, player, 0, new ItemStackRequestAction[]{action}, new HashMap<>()).ok());
            assertEquals(reverse ? 2 : 1, veto.calls);
            verify(source, never()).setItemStack(anyInt(), any(), anyBoolean());
            verify(destination, never()).setItemStack(anyInt(), any(), anyBoolean());
        } finally {
            Server.getInstance().getEventBus().unregisterListener(veto);
        }
    }
}
