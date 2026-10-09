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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemStackRequestPacketProcessorTest {

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
