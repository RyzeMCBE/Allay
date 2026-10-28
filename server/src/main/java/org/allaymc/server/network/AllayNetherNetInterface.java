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
import org.allaymc.server.network.protocol.ProtocolInfo;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
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

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real NetherNet listener: HTTP signaling over TCP + WebRTC ICE over UDP.
 * Runs beside the existing UDP RakNet listener, preserving legacy and NetEase support.
 * Requires Linux's libdatachannel native library at runtime.
 */
@Slf4j
public final class AllayNetherNetInterface extends AllayNetworkInterface {
    private final Server server;
    private volatile String motd;
    private volatile String subMotd;
    private final AtomicInteger players = new AtomicInteger();
    private volatile int maxPlayers;
    private MultiThreadIoEventLoopGroup group;
    private NetherNetHTTPServerSignaling signaling;
    private Channel channel;

    public AllayNetherNetInterface(Server server) {
        this.server = server;
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
        Preconditions.checkArgument(icePort > 0 && icePort <= 65535, "Invalid NetherNet WebRTC UDP port");
        Preconditions.checkArgument(icePort != config.port(), "NetherNet ICE UDP port cannot share RakNet IPv4 port");
        Preconditions.checkArgument(!config.enablev6() || icePort != config.portv6(),
                "NetherNet ICE UDP port cannot share RakNet IPv6 port");
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

            int threads = config.networkThreadNumber();
            Preconditions.checkArgument(threads >= 0, "networkThreadNumber cannot be negative");
            var factory = new ThreadFactoryBuilder().setNameFormat("NetherNet Server IO #%d").setDaemon(true).build();
            IoHandlerFactory ioHandlerFactory = NioIoHandler.newFactory();
            group = new MultiThreadIoEventLoopGroup(threads, factory, ioHandlerFactory);
            channel = new ServerBootstrap()
                    .group(group)
                    .channelFactory(NetherNetChannelFactory.server(signaling))
                    .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS,
                            new InetSocketAddress(config.ip(), icePort))
                    .childHandler(new AllayNetherNetInitializer())
                    .bind(new InetSocketAddress(config.ip(), tcpPort)).syncUninterruptibly().channel();
            log.info("NetherNet enabled: HTTP signaling TCP {}:{}, WebRTC UDP {}:{}",
                    config.ip(), tcpPort, config.ip(), icePort);
        } catch (Throwable failure) {
            shutdown();
            throw new IllegalStateException("Failed to start NetherNet (check Linux libdatachannel native and TCP/UDP ports)",
                    failure);
        }
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
