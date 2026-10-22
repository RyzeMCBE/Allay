package org.allaymc.server.network.processor.ingame;

import org.allaymc.server.network.protocol.PacketEncoder;
import org.allaymc.server.network.protocol.Protocol;
import org.allaymc.server.player.AllayPlayer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.*;
import org.allaymc.api.player.Player;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.allaymc.api.container.Container;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.eventbus.EventBus;
import org.allaymc.api.eventbus.event.player.PlayerInventoryRequestEvent;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.server.Server;
import org.allaymc.server.container.processor.ContainerActionProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemStackRequestPacketProcessorTest {

    @Test
    void nativeSlotResolutionDeliversACancellableImmutableRequest() {
        var player = mock(Player.class);
        var entity = mock(EntityPlayer.class);
        var container = mock(Container.class);
        var bus = mock(EventBus.class);
        var server = mock(Server.class);
        var name = new FullContainerName(ContainerSlotType.ARMOR,null);
        var slot = new ItemStackRequestSlotData(ContainerSlotType.ARMOR, 0, 1, name);
        when(player.getControlledEntity()).thenReturn(entity);
        when(container.getItemStackArray()).thenReturn(new ItemStack[4]);
        when(server.getEventBus()).thenReturn(bus);
        when(bus.callEvent(any())).thenAnswer(i -> {
            PlayerInventoryRequestEvent event = i.getArgument(0);
            assertEquals(entity,event.getPlayer());
            assertEquals(List.of(new PlayerInventoryRequestEvent.Slot(container,0)),event.getSlots());
            assertThrows(UnsupportedOperationException.class,()->event.getSlots().clear());
            event.cancel();return event;
        });
        try (var servers = mockStatic(Server.class); var containers = mockStatic(ContainerActionProcessor.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            containers.when(()->ContainerActionProcessor.getContainerFrom(player,name)).thenReturn(container);
            containers.when(()->ContainerActionProcessor.fromNetworkSlotIndex(container,0)).thenReturn(0);
            assertFalse(new ItemStackRequestPacketProcessor().allowRequest(player,new ItemStackRequestAction[]{new DestroyAction(1,slot)}));
        }
        assertThrows(IllegalArgumentException.class,()->new PlayerInventoryRequestEvent.Slot(container,4));
        assertThrows(IllegalArgumentException.class,()->new PlayerInventoryRequestEvent.Slot(container,-1));
    }

    @Test
    void exposesPartialTransfersSwapsAndOpaqueActionsInOrder() {
        var player = mock(Player.class);
        var entity = mock(EntityPlayer.class);
        var container = mock(Container.class);
        var server = mock(Server.class);
        var bus = mock(EventBus.class);
        var name = new FullContainerName(ContainerSlotType.INVENTORY, null);
        var source = new ItemStackRequestSlotData(ContainerSlotType.INVENTORY, 2, 41, name);
        var destination = new ItemStackRequestSlotData(ContainerSlotType.INVENTORY, 3, -7, name);
        var opaque = mock(ItemStackRequestAction.class);
        when(player.getControlledEntity()).thenReturn(entity);
        when(container.getItemStackArray()).thenReturn(new ItemStack[36]);
        when(server.getEventBus()).thenReturn(bus);
        when(bus.callEvent(any())).thenAnswer(invocation -> {
            PlayerInventoryRequestEvent event = invocation.getArgument(0);
            var actions = event.getActions();
            assertEquals(3, actions.size());
            assertEquals(PlayerInventoryRequestEvent.Kind.TRANSFER, actions.getFirst().kind());
            assertEquals(5, actions.getFirst().count());
            assertEquals(new PlayerInventoryRequestEvent.Slot(container, 2), actions.getFirst().source());
            assertEquals(new PlayerInventoryRequestEvent.Slot(container, 3), actions.getFirst().destination());
            assertEquals(41, actions.getFirst().sourceStackId());
            assertEquals(-7, actions.getFirst().destinationStackId());
            assertEquals(PlayerInventoryRequestEvent.Kind.SWAP, actions.get(1).kind());
            assertEquals(0, actions.get(1).count());
            assertEquals(PlayerInventoryRequestEvent.Kind.OTHER, actions.get(2).kind());
            assertThrows(UnsupportedOperationException.class, actions::clear);
            event.cancel();
            return event;
        });
        try (var servers = mockStatic(Server.class); var containers = mockStatic(ContainerActionProcessor.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            containers.when(() -> ContainerActionProcessor.getContainerFrom(player, name)).thenReturn(container);
            containers.when(() -> ContainerActionProcessor.fromNetworkSlotIndex(container, 2)).thenReturn(2);
            containers.when(() -> ContainerActionProcessor.fromNetworkSlotIndex(container, 3)).thenReturn(3);
            assertFalse(new ItemStackRequestPacketProcessor().allowRequest(player,
                    new ItemStackRequestAction[]{new TakeAction(5, source, destination), new SwapAction(destination, source), opaque}));
        }
    }

    @Test
    void legacyConstructionRemainsCompatibleAndInvalidActionShapesFailClosed() {
        var entity = mock(EntityPlayer.class);
        var container = mock(Container.class);
        when(container.getItemStackArray()).thenReturn(new ItemStack[1]);
        var slot = new PlayerInventoryRequestEvent.Slot(container, 0);
        assertTrue(new PlayerInventoryRequestEvent(entity, List.of(slot)).getActions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new PlayerInventoryRequestEvent.Action(
                PlayerInventoryRequestEvent.Kind.TRANSFER, 0, slot, slot, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerInventoryRequestEvent.Action(
                PlayerInventoryRequestEvent.Kind.SWAP, 1, slot, slot, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerInventoryRequestEvent.Action(
                PlayerInventoryRequestEvent.Kind.DROP, 1, slot, slot, 0, 0));
    }

    @Test
    void policyRejectionSkipsEveryActionIncludingEarlierActionsInTheRequest() {
        var player = mock(AllayPlayer.class);
        var protocol = mock(Protocol.class);
        var encoder = mock(PacketEncoder.class);
        var ordinary = new ItemStackRequestSlotData(ContainerSlotType.INVENTORY, 4, 1,
                new FullContainerName(ContainerSlotType.INVENTORY, null));
        var helmet = new ItemStackRequestSlotData(ContainerSlotType.ARMOR, 0, 2,
                new FullContainerName(ContainerSlotType.ARMOR, null));
        var actions = new ItemStackRequestAction[]{new DestroyAction(1, ordinary), new SwapAction(helmet, ordinary)};
        var packet = new ItemStackRequestPacket();
        packet.getRequests().add(new ItemStackRequest(-4, actions, new String[0]));
        when(player.getProtocol()).thenReturn(protocol);
        when(protocol.getEncoder()).thenReturn(encoder);
        when(encoder.encodeItemStackResponse(any())).thenAnswer(invocation -> {
            Collection<ItemStackResponse> responses = invocation.getArgument(0);
            var output = new ItemStackResponsePacket(); output.getEntries().addAll(responses); return output;
        });
        var processor = new ItemStackRequestPacketProcessor() {
            @Override boolean allowRequest(Player ignored, ItemStackRequestAction[] actual) {
                assertEquals(List.of(actions), List.of(actual));
                return false;
            }
        };
        processor.handleSync(player, packet, 0);
        verify(player, never()).getControlledEntity();
        var response = ArgumentCaptor.forClass(ItemStackResponsePacket.class);
        verify(player).sendPacket(response.capture());
        assertEquals(ItemStackResponseStatus.ERROR, response.getValue().getEntries().getFirst().result());
    }

    @Test
    void enumeratesBothSwapDirectionsAndAllExplicitRemovalSources() {
        var source = new ItemStackRequestSlotData(ContainerSlotType.ARMOR, 0, 1,
                new FullContainerName(ContainerSlotType.ARMOR, null));
        var destination = new ItemStackRequestSlotData(ContainerSlotType.INVENTORY, 4, 2,
                new FullContainerName(ContainerSlotType.INVENTORY, null));
        assertEquals(List.of(source,destination), ItemStackRequestPacketProcessor.referencedSlots(new SwapAction(source,destination)));
        assertEquals(List.of(source,destination), ItemStackRequestPacketProcessor.referencedSlots(new TakeAction(1,source,destination)));
        assertEquals(List.of(destination,source), ItemStackRequestPacketProcessor.referencedSlots(new PlaceAction(1,destination,source)));
        assertEquals(List.of(source), ItemStackRequestPacketProcessor.referencedSlots(new DestroyAction(1,source)));
        assertEquals(List.of(source), ItemStackRequestPacketProcessor.referencedSlots(new ConsumeAction(1,source)));
        assertEquals(List.of(source), ItemStackRequestPacketProcessor.referencedSlots(new DropAction(1,source,false)));
    }

    @Test
    void unsupportedActionsAreRejectedBeforeAnyInventoryMutation() {
        var player = mock(AllayPlayer.class);
        var protocol = mock(Protocol.class);
        var encoder = mock(PacketEncoder.class);
        var action = mock(ItemStackRequestAction.class);
        var request = new ItemStackRequest(-3, new ItemStackRequestAction[]{action}, new String[0]);
        var packet = new ItemStackRequestPacket();
        packet.getRequests().add(request);

        when(action.getType()).thenReturn(ItemStackRequestActionType.LAB_TABLE_COMBINE);
        when(player.getProtocol()).thenReturn(protocol);
        when(protocol.getEncoder()).thenReturn(encoder);
        when(encoder.encodeItemStackResponse(any())).thenAnswer(invocation -> {
            Collection<ItemStackResponse> responses = invocation.getArgument(0);
            var responsePacket = new ItemStackResponsePacket();
            responsePacket.getEntries().addAll(responses);
            return responsePacket;
        });

        new ItemStackRequestPacketProcessor().handleSync(player, packet, 0);

        var responsePacket = ArgumentCaptor.forClass(ItemStackResponsePacket.class);
        verify(player).sendPacket(responsePacket.capture());
        var response = responsePacket.getValue().getEntries().getFirst();
        assertEquals(-3, response.requestId());
        assertEquals(ItemStackResponseStatus.INVALID_REQUEST_ACTION_TYPE, response.result());
        assertFalse(response.success());
    }
}

