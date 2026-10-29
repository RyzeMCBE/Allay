package org.allaymc.server.network.nethernet;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tel.schich.libdatachannel.GatheringState;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class SharedPortHttpWebRtcTest {
    @Test
    @Timeout(35)
    void signedHttpOfferAuthorizesTheSharedPortAndCompletesIceAndDtls() throws Exception {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        var multiplexer = new NetherNetUdpMultiplexer();
        Channel front = null;
        Channel server = null;
        SharedPortNetherNetSignaling signaling = null;
        SharedPortEndpointResolver resolver = null;
        try {
            var backend = multiplexer.start();
            front = new ServerBootstrap().group(group)
                    .channelFactory(RakChannelFactory.server(NioDatagramChannel.class,
                            datagram -> multiplexer.attach(datagram, NioDatagramChannel.class)))
                    .option(RakChannelOption.RAK_ADVERTISEMENT, Unpooled.wrappedBuffer(new byte[]{1}))
                    .childHandler(new ChannelInboundHandlerAdapter())
                    .bind("127.0.0.1", 0).sync().channel();
            var publicAddress = (InetSocketAddress) front.localAddress();
            var joinEndpoints = new SharedPortEndpointResolver(publicAddress.getPort(), publicAddress.getPort(), false);
            resolver = joinEndpoints;
            var delegate = new NetherNetHTTPServerSignaling.Builder()
                    .setIdentity(OperatorIdentity.generate("server.example.test"))
                    // A test identity only; the production listener retains MINECRAFT_AUTH.
                    .setTokenTrust(TokenTrust.ANY)
                    .setPlayerFilter((host, player) -> {
                        joinEndpoints.prepare(player.networkId(), host);
                        return null;
                    }).build();
            signaling = new SharedPortNetherNetSignaling(delegate, multiplexer, publicAddress,
                    List.of(publicAddress), joinEndpoints);
            var active = new CompletableFuture<Channel>();
            server = new ServerBootstrap().group(group)
                    .channelFactory(NetherNetChannelFactory.server(signaling))
                    .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS, backend)
                    .option(NetherChannelOption.NETHER_SERVER_PROXY_PROTOCOL, IpRangeSet.parse(List.of("127.0.0.1")))
                    .childHandler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            active.complete(ctx.channel());
                            ctx.fireChannelActive();
                        }
                    }).bind(publicAddress).sync().channel();

            try (var client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT
                    .withBindAddress(publicAddress.getAddress()).withDisableAutoNegotiation(true))) {
                client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
                client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
                var gathered = new CompletableFuture<String>();
                client.onGatheringStateChange.register((peer, state) -> {
                    if (state == GatheringState.RTC_GATHERING_COMPLETE) gathered.complete(peer.localDescription());
                });
                client.setLocalDescription("offer");
                String signed = OperatorIdentity.generate("client.example.test").forPlayer("2535000000000000", "Probe")
                        .withAssertion(gathered.get(5, TimeUnit.SECONDS));
                String answer = post(publicAddress, signed);
                assertTrue(answer.contains("a=identity:"));
                assertFalse(answer.contains(" " + backend.getPort() + " typ "));
                client.setRemoteDescription(answer, SessionDescriptionType.ANSWER);
                var connected = active.get(10, TimeUnit.SECONDS);
                assertTrue(connected.isActive());
                connected.close().sync();
            }
        } finally {
            if (server != null) server.close().sync();
            if (signaling != null) signaling.close();
            if (resolver != null) resolver.close();
            multiplexer.close();
            if (front != null) front.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private static String post(InetSocketAddress endpoint, String offer) throws Exception {
        byte[] body = offer.getBytes(StandardCharsets.UTF_8);
        String headers = "POST /v1/join/42 HTTP/1.1\r\nHost: 127.0.0.1:" + endpoint.getPort()
                + "\r\nContent-Type: application/sdp\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        try (var socket = new Socket()) {
            socket.connect(endpoint, 3000);
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int separator = response.indexOf("\r\n\r\n");
            assertTrue(separator >= 0);
            assertTrue(response.startsWith("HTTP/1.1 200"), response.substring(0, response.indexOf("\r\n")));
            return response.substring(separator + 4);
        }
    }
}
