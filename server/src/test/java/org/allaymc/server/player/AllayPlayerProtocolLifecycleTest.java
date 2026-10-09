package org.allaymc.server.player;

import org.allaymc.api.player.ClientState;
import org.allaymc.server.network.AllayNetworkInterface;
import org.allaymc.server.network.processor.PacketProcessorHolder;
import org.allaymc.server.network.processor.PacketProcessorRegistry;
import org.allaymc.server.network.protocol.Protocol;
import org.allaymc.server.network.protocol.ProtocolData;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class AllayPlayerProtocolLifecycleTest {

    @Test
    void emptyEncoderResultsAreIgnoredAtTheSendBoundary() {
        var player = new TestPlayer(mock(BedrockServerSession.class), mock(AllayNetworkInterface.class));

        player.sendPacket(null);
        player.sendPackets(null);
        player.sendPackets(List.of());
        player.sendPacketImmediately(null);
        player.sendPacketsImmediately(null);
        player.sendPacketsImmediately(List.of());
    }

    @Test
    void chunkPublisherUpdatesAreOnlySentWhenCenterOrRadiusChanges() {
        var player = new PublisherTestPlayer(mock(BedrockServerSession.class), mock(AllayNetworkInterface.class));

        player.setPublisherUpdate(Vector3i.from(1, 2, 3), 128);
        player.sendNetworkChunkPublisherUpdateIfNeeded();
        player.sendNetworkChunkPublisherUpdateIfNeeded();
        assertEquals(1, player.sentPackets.size());

        player.setPublisherUpdate(Vector3i.from(1, 2, 3), 160);
        player.sendNetworkChunkPublisherUpdateIfNeeded();
        assertEquals(2, player.sentPackets.size());

        player.setPublisherUpdate(Vector3i.from(4, 2, 3), 160);
        player.sendNetworkChunkPublisherUpdateIfNeeded();
        assertEquals(3, player.sentPackets.size());
    }

    @Test
    void disconnectWaitsForProtocolInstallAndTransitionsInstalledSession() throws Exception {
        var handler = new AtomicReference<BedrockPacketHandler>();
        var bedrockSession = sessionCapturingHandler(handler);
        var player = new TestPlayer(bedrockSession, mock(AllayNetworkInterface.class));
        var protocol = protocolForInstall(bedrockSession);
        var installReachedConnectionCheck = new CountDownLatch(1);
        var allowInstallToContinue = new CountDownLatch(1);
        when(bedrockSession.isConnected()).thenAnswer(invocation -> {
            installReachedConnectionCheck.countDown();
            assertTrue(allowInstallToContinue.await(5, TimeUnit.SECONDS));
            return true;
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var install = executor.submit(() -> player.installProtocol(protocol));
            assertTrue(installReachedConnectionCheck.await(5, TimeUnit.SECONDS));
            var disconnect = executor.submit(() -> handler.get().onDisconnect("closed"));

            allowInstallToContinue.countDown();

            assertTrue(install.get(5, TimeUnit.SECONDS));
            disconnect.get(5, TimeUnit.SECONDS);
        }

        assertNotNull(player.getProtocolSession());
        assertEquals(ClientState.DISCONNECTED, player.getClientState());
        assertEquals(1, player.disconnectCallbacks.get());
    }

    @Test
    void protocolCannotBeInstalledAfterDisconnectCallbackWins() {
        var handler = new AtomicReference<BedrockPacketHandler>();
        var bedrockSession = sessionCapturingHandler(handler);
        when(bedrockSession.isConnected()).thenReturn(true);
        var player = new TestPlayer(bedrockSession, mock(AllayNetworkInterface.class));

        handler.get().onDisconnect("closed");

        assertFalse(player.installProtocol(mock(Protocol.class)));
        assertNull(player.getProtocolSession());
        assertEquals(ClientState.DISCONNECTED, player.getClientState());
        assertEquals(1, player.disconnectCallbacks.get());
    }

    private static BedrockServerSession sessionCapturingHandler(AtomicReference<BedrockPacketHandler> handler) {
        var session = mock(BedrockServerSession.class);
        doAnswer(invocation -> {
            handler.set(invocation.getArgument(0));
            return null;
        }).when(session).setPacketHandler(any(BedrockPacketHandler.class));
        return session;
    }

    private static Protocol protocolForInstall(BedrockServerSession session) {
        var registry = new PacketProcessorRegistry();
        registry.freeze();
        PacketProcessorHolder holder = new PacketProcessorHolder(registry);
        var protocol = mock(Protocol.class);
        var data = mock(ProtocolData.class);
        var codec = mock(BedrockCodec.class);
        var bootstrapCodec = mock(BedrockCodec.class);
        var peer = mock(BedrockPeer.class);
        var codecHelper = mock(BedrockCodecHelper.class);

        when(protocol.isInitialized()).thenReturn(true);
        when(protocol.createProcessorHolder()).thenReturn(holder);
        when(protocol.getCodec()).thenReturn(codec);
        when(protocol.getData()).thenReturn(data);
        when(data.itemDefinitions()).thenReturn(List.of());
        when(data.blockDefinitions()).thenReturn(List.of());
        when(session.getCodec()).thenReturn(bootstrapCodec);
        when(session.getPeer()).thenReturn(peer);
        when(peer.getCodecHelper()).thenReturn(codecHelper);
        return protocol;
    }

    private static final class PublisherTestPlayer extends AllayPlayer {
        private final List<BedrockPacket> sentPackets = new ArrayList<>();
        private NetworkChunkPublisherUpdatePacket publisherUpdate;

        private PublisherTestPlayer(BedrockServerSession session, AllayNetworkInterface sourceInterface) {
            super(session, sourceInterface);
        }

        private void setPublisherUpdate(Vector3i position, int radius) {
            publisherUpdate = new NetworkChunkPublisherUpdatePacket();
            publisherUpdate.setPosition(position);
            publisherUpdate.setRadius(radius);
        }

        @Override
        protected NetworkChunkPublisherUpdatePacket createNetworkChunkPublisherUpdatePacket() {
            return publisherUpdate;
        }

        @Override
        public void sendPacket(BedrockPacket packet) {
            sentPackets.add(packet);
        }
    }

    private static final class TestPlayer extends AllayPlayer {
        private final AtomicInteger disconnectCallbacks = new AtomicInteger();

        private TestPlayer(BedrockServerSession session, AllayNetworkInterface sourceInterface) {
            super(session, sourceInterface);
        }

        @Override
        protected void onDisconnect(String disconnectReason) {
            disconnectCallbacks.incrementAndGet();
        }
    }
}
