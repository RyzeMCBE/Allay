package org.allaymc.server.player;

import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.item.type.ItemTypes;
import org.allaymc.server.container.impl.BaseContainer;
import org.allaymc.server.container.impl.PlayerArmorContainerImpl;
import org.allaymc.server.container.processor.ContainerActionProcessor;
import org.allaymc.server.container.processor.TakeActionProcessor;
import org.allaymc.server.network.AllayNetworkInterface;
import org.allaymc.server.network.protocol.ClientVariant;
import org.allaymc.server.network.protocol.Protocol;
import org.allaymc.server.network.protocol.ProtocolRegistry;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class ArmorInventorySyncTest {
    @Test
    void autoEquippedArmorCanBeRemovedUsingTheNetworkIdSentToTheClient() {
        for (int version : new int[]{818, 844, 2168, 2169, 2193}) {
            var protocol = ProtocolRegistry.getDefault().resolve(ClientVariant.INTERNATIONAL, version);
            var player = new RecordingPlayer(protocol);
            var actor = mock(EntityPlayer.class);
            when(actor.isActualPlayer()).thenReturn(true);
            when(actor.getController()).thenReturn(player);
            var armor = new PlayerArmorContainerImpl(() -> actor);
            var cursor = new BaseContainer(ContainerTypes.CURSOR);
            var helmet = ItemTypes.DIAMOND_HELMET.createItemStack(1);
            armor.setHelmet(helmet);

            var packet = assertInstanceOf(InventoryContentPacket.class, player.packets.getLast());
            assertEquals(4, packet.getContents().size());
            assertEquals(helmet.getUniqueId(), packet.getContents().getFirst().getNetId());
            var from = new FullContainerName(ContainerSlotType.ARMOR, null);
            var to = new FullContainerName(ContainerSlotType.CURSOR, null);
            var action = new TakeAction(1,
                    new ItemStackRequestSlotData(ContainerSlotType.ARMOR, 0,
                            packet.getContents().getFirst().getNetId(), from),
                    new ItemStackRequestSlotData(ContainerSlotType.CURSOR, 0, 0, to));
            try (var access = mockStatic(ContainerActionProcessor.class)) {
                access.when(() -> ContainerActionProcessor.getContainerFrom(player, from)).thenReturn(armor);
                access.when(() -> ContainerActionProcessor.getContainerFrom(player, to)).thenReturn(cursor);
                access.when(() -> ContainerActionProcessor.canPlaceItemToSlot(cursor, 0, helmet)).thenReturn(true);
                access.when(() -> ContainerActionProcessor.getSlotType(armor, 0)).thenReturn(ContainerSlotType.ARMOR);
                access.when(() -> ContainerActionProcessor.getSlotType(cursor, 0)).thenReturn(ContainerSlotType.CURSOR);
                var result = new TakeActionProcessor().handle(action, player, 0,
                        new ItemStackRequestAction[]{action}, new HashMap<>());
                assertTrue(result.ok(), "protocol " + version);
            }
            assertTrue(armor.isEmpty(0));
            assertEquals(helmet.getUniqueId(), cursor.getItemStack(0).getUniqueId());
        }
    }

    private static final class RecordingPlayer extends AllayPlayer {
        private final Protocol protocol;
        private final List<BedrockPacket> packets = new ArrayList<>();

        private RecordingPlayer(Protocol protocol) {
            super(mock(BedrockServerSession.class), mock(AllayNetworkInterface.class));
            this.protocol = protocol;
        }

        @Override public Protocol getProtocol() { return protocol; }
        @Override public void sendPacket(BedrockPacket packet) { packets.add(packet); }
        @Override public EntityPlayer getControlledEntity() { return mock(EntityPlayer.class); }
    }
}
