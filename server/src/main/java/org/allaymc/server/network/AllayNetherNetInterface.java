package org.allaymc.server.network;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.server.Server;
import org.allaymc.server.AllayServer;
import org.allaymc.server.eventbus.event.network.NettyPipelineInitEvent;
import org.allaymc.server.network.nethernet.AllayNetherNetPeer;
import org.allaymc.server.network.nethernet.NetherNetFrameCodec;
import org.allaymc.server.network.nethernet.NetherNetPacketEncoder;
import org.allaymc.server.network.nethernet.NetherNetUdpMultiplexer;
import org.allaymc.server.network.nethernet.SharedPortNetherNetSignaling;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NetherNet listener with TCP signaling and WebRTC on shared or dedicated UDP.
 * Preserves the RakNet listener for legacy and NetEase support.
 * Requires Linux's libdatachannel native library at runtime.
 */
@Slf4j
public final class AllayNetherNetInterface extends AllayNetworkInterface {
    private final Server server;
    private final NetherNetUdpMultiplexer sharedUdp;
    private volatile String motd;
    private volatile String subMotd;
    private final AtomicInteger players = new AtomicInteger();
    private volatile int maxPlayers;
    private MultiThreadIoEventLoopGroup group;
    private NetherNetHTTPServerSignaling signaling;
    private Channel channel;

    public AllayNetherNetInterface(Server server) {
        this(server, null);
    }

    public AllayNetherNetInterface(Server server, NetherNetUdpMultiplexer sharedUdp) {
        this.server = server;
        this.sharedUdp = sharedUdp;
        var generic = AllayServer.getSettings().genericSettings();
        this.motd = generic.motd();
        this.subMotd = generic.subMotd();
        this.maxPlayers = generic.maxPlayerCount();
    }

    @Override
    public boolean isTransportEncrypted() {
        return true;
    }

    @Override
    public synchronized void start() {
        if (channel != null) return;
        var config = AllayServer.getSettings().networkSettings();
        int tcpPort = config.nethernetSignalingPort() == 0 ? config.port() : config.nethernetSignalingPort();
        int icePort = config.nethernetIcePort();
        Preconditions.checkArgument(tcpPort > 0 && tcpPort <= 65535, "Invalid NetherNet TCP signaling port");
        if (sharedUdp == null) {
            Preconditions.checkArgument(icePort > 0 && icePort <= 65535, "Invalid NetherNet WebRTC UDP port");
            Preconditions.checkArgument(icePort != config.port(), "Enable nethernet-shared-port to share the RakNet UDP port");
            Preconditions.checkArgument(!config.enablev6() || icePort != config.portv6(),
                    "Dedicated NetherNet ICE UDP port cannot share RakNet IPv6 port");
        }
        Preconditions.checkArgument(!config.nethernetIdentityFile().isBlank(), "NetherNet identity PEM file is required");

        try {
            OperatorIdentity identity = OperatorIdentity.fromPemOrCreate(
                    Path.of(config.nethernetIdentityFile()).toFile(), "Allay-" + motd);
            var builder = new NetherNetHTTPServerSignaling.Builder()
                    .setIdentity(identity)
                    .setTokenTrust(TokenTrust.MINECRAFT_AUTH)
                    .setMotdProvider((host, remote, client) -> createPong())
                    .setMotd(createPong())
                    .setIceOnLocalPort(false);
            if (!config.nethernetAdvertiseAddresses().isEmpty()) {
                builder.setAdvertisedAddresses(config.nethernetAdvertiseAddresses());
            }
            signaling = builder.build();

            InetSocketAddress iceAddress = sharedUdp == null
                    ? new InetSocketAddress(config.ip(), icePort) : sharedUdp.start();
            NetherNetServerSignaling transportSignaling = signaling;
            if (sharedUdp != null) {
                transportSignaling = new SharedPortNetherNetSignaling(signaling, sharedUdp,
                        new InetSocketAddress(config.ip(), config.port()), sharedHostEndpoints(config.ip(), config.port()));
            }

            int threads = config.networkThreadNumber();
            Preconditions.checkArgument(threads >= 0, "networkThreadNumber cannot be negative");
            var factory = new ThreadFactoryBuilder().setNameFormat("NetherNet Server IO #%d").setDaemon(true).build();
            IoHandlerFactory ioHandlerFactory = NioIoHandler.newFactory();
            group = new MultiThreadIoEventLoopGroup(threads, factory, ioHandlerFactory);
            var bootstrap = new ServerBootstrap()
                    .group(group)
                    .channelFactory(NetherNetChannelFactory.server(transportSignaling))
                    .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS, iceAddress)
                    .childHandler(new AllayNetherNetInitializer());
            if (sharedUdp != null) {
                bootstrap.option(NetherChannelOption.NETHER_SERVER_PROXY_PROTOCOL, IpRangeSet.parse(List.of("127.0.0.1")));
            }
            channel = bootstrap.bind(new InetSocketAddress(config.ip(), tcpPort)).syncUninterruptibly().channel();
            if (sharedUdp == null) {
                log.info("NetherNet enabled: HTTP signaling TCP {}:{}, WebRTC UDP {}:{}",
                        config.ip(), tcpPort, config.ip(), icePort);
            } else {
                log.info("NetherNet enabled: HTTP signaling TCP {}:{}, shared RakNet/WebRTC UDP {}:{}",
                        config.ip(), tcpPort, config.ip(), config.port());
            }
        } catch (Throwable failure) {
            shutdown();
            throw new IllegalStateException("Failed to start NetherNet (check Linux libdatachannel native and TCP/UDP ports)",
                    failure);
        }
    }

    private static List<InetSocketAddress> sharedHostEndpoints(String host, int port) throws SocketException {
        var address = new InetSocketAddress(host, port);
        if (!address.getAddress().isAnyLocalAddress()) return List.of(address);
        return NetworkInterface.networkInterfaces().flatMap(NetworkInterface::inetAddresses)
                .filter(ip -> ip instanceof Inet4Address && !ip.isLinkLocalAddress())
                .map(ip -> new InetSocketAddress(ip, port)).distinct().toList();
    }

    private PongData createPong() {
        return new PongData.Builder()
                .setServerName(motd)
                .setLevelName(subMotd)
                .setProtocol(ProtocolInfo.getLatestCodec().getProtocolVersion())
                .setVersion(ProtocolInfo.getLatestCodec().getMinecraftVersion())
                .setPlayerCount(players.get())
                .setMaxPlayerCount(maxPlayers)
                .setSelfSignedAuth(!AllayServer.getSettings().networkSettings().xboxAuth())
                .build();
    }

    @Override
    public synchronized void shutdown() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
            channel = null;
        }
        if (signaling != null) {
            signaling.close();
            signaling = null;
        }
        if (sharedUdp != null) sharedUdp.close();
        if (group != null) {
            group.shutdownGracefully().syncUninterruptibly();
            group = null;
        }
    }

    @Override
    public int getPing(BedrockServerSession session) {
        // No RakNet ping exists on a WebRTC data channel.
        return -1;
    }

    @Override
    public String getMotd() {
        return motd;
    }

    @Override
    public void setMotd(String motd) {
        this.motd = motd;
    }

    @Override
    public String getSubMotd() {
        return subMotd;
    }

    @Override
    public void setSubMotd(String subMotd) {
        this.subMotd = subMotd;
    }

    @Override
    public void setPlayerCount(int count) {
        players.set(count);
    }

    @Override
    public void setMaxPlayerCount(int maxPlayerCount) {
        maxPlayers = maxPlayerCount;
    }

    private final class AllayNetherNetInitializer extends BedrockServerInitializer {
        @Override
        protected void preInitChannel(Channel child) {
            // No RakNet 0xFE frame; DTLS already encrypts the transport.
            child.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND);
            child.pipeline()
                    .addLast(NetherNetFrameCodec.NAME, NetherNetFrameCodec.INSTANCE)
                    .addLast(CompressionCodec.NAME,
                            new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()), false));
            new NettyPipelineInitEvent(child).call();
        }

        @Override
        protected void initPacketCodec(Channel child) {
            child.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
        }

        @Override
        protected void postInitChannel(Channel child) {
            child.pipeline().replace(BedrockBatchEncoder.NAME,
                    NetherNetPacketEncoder.NAME, NetherNetPacketEncoder.INSTANCE);
        }

        @Override
        protected BedrockPeer createPeer(Channel child) {
            return new AllayNetherNetPeer(child, this::createSession);
        }

        @Override
        protected void initSession(BedrockServerSession session) {
            initPlayerSession(session);
        }
    }
}
