package org.allaymc.server.network.nethernet;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.util.nethernet.IceProxyProtocol;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback;
import tel.schich.libdatachannel.GatheringState;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class SharedUdpWebRtcTest {
    private static final InetAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0).getAddress();
    private static final PeerConnectionConfiguration CONFIG = PeerConnectionConfiguration.DEFAULT
            .withBindAddress(LOOPBACK).withDisableAutoNegotiation(true);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(75)
    void sixConcurrentRealWebRtcPeersAndLegacyRaknetShareOnePublicSocket(boolean epoll) throws Exception {
        Assumptions.assumeTrue(!epoll || Epoll.isAvailable());
        Class<? extends DatagramChannel> datagramClass = epoll ? EpollDatagramChannel.class : NioDatagramChannel.class;
        var group = new MultiThreadIoEventLoopGroup(1, epoll ? EpollIoHandler.newFactory() : NioIoHandler.newFactory());
        var multiplexer = new NetherNetUdpMultiplexer();
        var peers = new ArrayList<PeerConnection>();
        Channel front = null;
        IceProxyProtocol proxy = null;
        try {
            var backend = multiplexer.start();
            proxy = IceProxyProtocol.set(LOOPBACK, backend.getPort(), IpRangeSet.parse(List.of("127.0.0.1")));
            front = new ServerBootstrap().group(group)
                    .channelFactory(RakChannelFactory.server(datagramClass,
                            datagram -> multiplexer.attach(datagram, datagramClass)))
                    .option(RakChannelOption.RAK_ADVERTISEMENT, Unpooled.wrappedBuffer("shared-port".getBytes(StandardCharsets.UTF_8)))
                    .childHandler(new ChannelInboundHandlerAdapter())
                    .bind("0.0.0.0", 0).sync().channel();
            var publicAddress = new InetSocketAddress(LOOPBACK, ((InetSocketAddress) front.localAddress()).getPort());
            assertNotEquals(backend.getPort(), publicAddress.getPort());
            assertRaknetPong(publicAddress);

            var clients = new ArrayList<DataChannel>();
            var servers = new ArrayList<DataChannel>();
            var openedChannels = new ArrayList<CompletableFuture<Void>>();
            var acceptedChannels = new ArrayList<CompletableFuture<DataChannel>>();
            var receivedByServer = new ArrayList<LinkedBlockingQueue<String>>();
            var receivedByClient = new ArrayList<LinkedBlockingQueue<String>>();
            for (int i = 0; i < 6; i++) {
                var server = PeerConnection.createPeer(CONFIG.withEnableIceUdpMux(true).withIceUdpMuxDirect(true)
                        .withPortRangeBegin(backend.getPort()).withPortRangeEnd(backend.getPort()));
                var client = PeerConnection.createPeer(CONFIG);
                peers.add(server);
                peers.add(client);
                var toServer = new LinkedBlockingQueue<String>();
                var toClient = new LinkedBlockingQueue<String>();
                receivedByServer.add(toServer);
                receivedByClient.add(toClient);
                var accepted = new CompletableFuture<DataChannel>();
                server.onDataChannel.register((pc, dc) -> {
                    dc.onMessage.register(DataChannelCallback.Message.handleBinary((channel, data) -> toServer.add(text(data))));
                    accepted.complete(dc);
                });
                var outbound = client.createDataChannel("test-reliable");
                var opened = new CompletableFuture<Void>();
                outbound.onOpen.register(dc -> opened.complete(null));
                outbound.onMessage.register(DataChannelCallback.Message.handleBinary((channel, data) -> toClient.add(text(data))));

                String offer = gather(client, "offer");
                // No direct candidate reaches the server: all ICE and DTLS must cross the shared port.
                server.setRemoteDescription(SdpUtil.withIpCandidates(offer, 0), SessionDescriptionType.OFFER);
                String answer = gather(server, "answer");
                String ufrag = answer.lines().filter(line -> line.startsWith("a=ice-ufrag:"))
                        .map(line -> line.substring(12)).findFirst().orElseThrow();
                multiplexer.authorize(ufrag);
                String publicAnswer = SharedPortNetherNetSignaling.publicDescription(answer, List.of(publicAddress));
                assertFalse(publicAnswer.contains(" " + backend.getPort() + " typ "));
                client.setRemoteDescription(publicAnswer, SessionDescriptionType.ANSWER);
                // Start all six ICE handshakes before awaiting any of them.
                // This exercises overlapping signaling/relays with one public UDP port.
                openedChannels.add(opened);
                acceptedChannels.add(accepted);
                clients.add(outbound);
            }

            for (var opened : openedChannels) opened.get(20, TimeUnit.SECONDS);
            for (var accepted : acceptedChannels) servers.add(accepted.get(10, TimeUnit.SECONDS));

            for (int i = 0; i < clients.size(); i++) clients.get(i).sendMessage(binary("client-" + i));
            for (int i = 0; i < clients.size(); i++) {
                assertEquals("client-" + i, receivedByServer.get(i).poll(5, TimeUnit.SECONDS));
                servers.get(i).sendMessage(binary("server-" + i));
            }
            for (int i = 0; i < clients.size(); i++) {
                assertEquals("server-" + i, receivedByClient.get(i).poll(5, TimeUnit.SECONDS));
                assertTrue(receivedByServer.get(i).isEmpty());
                assertTrue(receivedByClient.get(i).isEmpty());
            }
            assertRaknetPong(publicAddress);
        } finally {
            for (var peer : peers) peer.close();
            if (proxy != null) proxy.close();
            multiplexer.close();
            if (front != null) front.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private static String gather(PeerConnection peer, String type) throws Exception {
        var gathered = new CompletableFuture<String>();
        peer.onGatheringStateChange.register((pc, state) -> {
            if (state == GatheringState.RTC_GATHERING_COMPLETE) gathered.complete(pc.localDescription());
        });
        peer.setLocalDescription(type);
        return gathered.get(5, TimeUnit.SECONDS);
    }

    private static String text(ByteBuffer data) {
        return StandardCharsets.UTF_8.decode(data).toString();
    }

    private static ByteBuffer binary(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
    }

    private static void assertRaknetPong(InetSocketAddress address) throws Exception {
        byte[] magic = {0, -1, -1, 0, -2, -2, -2, -2, -3, -3, -3, -3, 18, 52, 86, 120};
        byte[] ping = ByteBuffer.allocate(33).put((byte) 1).putLong(1).put(magic).putLong(2).array();
        try (var socket = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            socket.setSoTimeout(5000);
            socket.send(new DatagramPacket(ping, ping.length, address));
            var pong = new DatagramPacket(new byte[2048], 2048);
            socket.receive(pong);
            assertEquals(0x1c, pong.getData()[0] & 0xff);
            assertEquals(address.getPort(), pong.getPort());
        }
    }
}
