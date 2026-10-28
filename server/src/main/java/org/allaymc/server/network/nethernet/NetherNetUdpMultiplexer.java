package org.allaymc.server.network.nethernet;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.channel.nethernet.NetherNetNative;
import tel.schich.libdatachannel.IceUdpMuxListener;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Demultiplexes RakNet and authenticated ICE/DTLS on the existing Bedrock UDP socket.
 * WebRTC reaches the native transport through local PROXY v2 relays, preserving each
 * client's original address. Only the public Bedrock port is advertised to clients.
 */
public final class NetherNetUdpMultiplexer implements AutoCloseable {
    private static final InetAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0).getAddress();
    private static final int MAX_RELAYS = 1024;
    private static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final byte[] PROXY_SIGNATURE = {13, 10, 13, 10, 0, 13, 10, 81, 85, 73, 84, 10};

    private final Set<String> authorizedUfrags = ConcurrentHashMap.newKeySet();
    private final Set<FrontHandler> fronts = ConcurrentHashMap.newKeySet();
    private IceUdpMuxListener nativeListener;
    private volatile InetSocketAddress backendAddress;

    /** Reserves a native ICE endpoint, accepting traffic only through the local relays. */
    public synchronized InetSocketAddress start() throws IOException {
        if (nativeListener != null) return backendAddress;
        NetherNetNative.initialize();
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            int port;
            try (var available = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
                port = available.getLocalPort();
            }
            try {
                var listener = new IceUdpMuxListener(LOOPBACK, port, Runnable::run,
                        request -> CompletableFuture.completedFuture(null));
                try {
                    // Existing, signaled peers bypass admission; unsolicited peers never reach Java.
                    listener.setAdmission(new byte[0], Map.of());
                } catch (Throwable failure) {
                    listener.close();
                    throw failure;
                }
                nativeListener = listener;
                backendAddress = new InetSocketAddress(LOOPBACK, port);
                return backendAddress;
            } catch (IllegalStateException busy) {
                // The OS-selected port was released immediately before the native socket claimed it.
                lastFailure = busy;
            }
        }
        throw new IOException("Cannot reserve a private NetherNet ICE endpoint", lastFailure);
    }

    /** Installs routing before RakNet sees datagrams, without changing RakNet's socket or client addresses. */
    public void attach(DatagramChannel channel, Class<? extends DatagramChannel> channelClass) {
        var handler = new FrontHandler(channelClass);
        fronts.add(handler);
        channel.pipeline().addFirst("nethernet-udp-multiplexer", handler);
    }

    /** Allows a signaled peer's ICE username fragment until its channel closes. */
    public void authorize(String ufrag) {
        authorizedUfrags.add(ufrag);
    }

    /** Releases every candidate path belonging to a closed peer. */
    public void revoke(String ufrag) {
        authorizedUfrags.remove(ufrag);
        for (var front : fronts) front.removeUfrag(ufrag);
    }

    @Override
    public synchronized void close() {
        backendAddress = null;
        authorizedUfrags.clear();
        for (var front : fronts) front.clearAsync();
        if (nativeListener != null) {
            nativeListener.close();
            nativeListener = null;
        }
    }

    static boolean isStun(ByteBuf data) {
        int i = data.readerIndex();
        return data.readableBytes() >= 20 && (data.getUnsignedByte(i) & 0xc0) == 0
                && data.getInt(i + 4) == 0x2112a442
                && (data.getUnsignedShort(i + 2) & 3) == 0
                && data.getUnsignedShort(i + 2) == data.readableBytes() - 20;
    }

    static boolean isDtls(ByteBuf data) {
        int i = data.readerIndex();
        return data.readableBytes() >= 13 && data.getUnsignedByte(i) >= 20
                && data.getUnsignedByte(i) <= 63 && data.getUnsignedByte(i + 1) == 0xfe
                && (data.getUnsignedByte(i + 2) == 0xfd || data.getUnsignedByte(i + 2) == 0xff);
    }

    static String requestedUfrag(ByteBuf data) {
        if (!isStun(data) || data.getUnsignedShort(data.readerIndex()) != 1) return null;
        int end = data.writerIndex();
        for (int i = data.readerIndex() + 20; i + 4 <= end; ) {
            int type = data.getUnsignedShort(i);
            int length = data.getUnsignedShort(i + 2);
            i += 4;
            if (length > end - i) return null;
            if (type == 6 && length > 0 && length <= 512) {
                String username = data.getCharSequence(i, length, StandardCharsets.US_ASCII).toString();
                int colon = username.indexOf(':');
                return colon > 0 ? username.substring(0, colon) : null;
            }
            i += (length + 3) & ~3;
        }
        return null;
    }

    static ByteBuf proxyPacket(ByteBufAllocator allocator, InetSocketAddress source,
                               InetSocketAddress destination, ByteBuf payload) {
        byte[] sourceBytes = source.getAddress().getAddress();
        byte[] destinationBytes = destination.getAddress().getAddress();
        if (destinationBytes.length != sourceBytes.length) destinationBytes = new byte[sourceBytes.length];
        int addressLength = sourceBytes.length * 2 + 4;
        return allocator.buffer(16 + addressLength + payload.readableBytes())
                .writeBytes(PROXY_SIGNATURE).writeByte(0x21)
                .writeByte(sourceBytes.length == 16 ? 0x22 : 0x12).writeShort(addressLength)
                .writeBytes(sourceBytes).writeBytes(destinationBytes)
                .writeShort(source.getPort()).writeShort(destination.getPort())
                .writeBytes(payload, payload.readerIndex(), payload.readableBytes());
    }

    private final class FrontHandler extends ChannelInboundHandlerAdapter {
        private final Class<? extends DatagramChannel> channelClass;
        private final Map<InetSocketAddress, Relay> relays = new HashMap<>();
        private ChannelHandlerContext context;
        private ScheduledFuture<?> cleanup;

        private FrontHandler(Class<? extends DatagramChannel> channelClass) {
            this.channelClass = channelClass;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            context = ctx;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            cleanup = ctx.executor().scheduleAtFixedRate(() -> {
                long now = System.nanoTime();
                relays.values().removeIf(relay -> {
                    if (now - relay.lastActivity < IDLE_NANOS) return false;
                    relay.close();
                    return true;
                });
            }, 30, 30, TimeUnit.SECONDS);
            ctx.fireChannelActive();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket packet)
                    || (!isStun(packet.content()) && !isDtls(packet.content()))) {
                ctx.fireChannelRead(msg);
                return;
            }
            try {
                var backend = backendAddress;
                if (backend == null) return;
                var relay = relays.get(packet.sender());
                String ufrag = requestedUfrag(packet.content());
                if (ufrag != null && !authorizedUfrags.contains(ufrag)) return;
                if (relay != null && ufrag != null && !ufrag.equals(relay.ufrag)) {
                    relay.close();
                    relays.remove(packet.sender());
                    relay = null;
                }
                if (relay == null) {
                    if (ufrag == null || !authorizedUfrags.contains(ufrag) || relays.size() >= MAX_RELAYS) return;
                    relay = new Relay(packet.sender(), ufrag, backend);
                    relays.put(packet.sender(), relay);
                }
                if (!authorizedUfrags.contains(relay.ufrag)) return;
                relay.send(proxyPacket(ctx.alloc(), packet.sender(), packet.recipient(), packet.content()));
            } finally {
                ReferenceCountUtil.release(packet);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            clear();
            ctx.fireChannelInactive();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            clear();
            fronts.remove(this);
        }

        private void removeUfrag(String ufrag) {
            context.executor().execute(() -> relays.values().removeIf(relay -> {
                if (!relay.ufrag.equals(ufrag)) return false;
                relay.close();
                return true;
            }));
        }

        private void clearAsync() {
            if (context != null) context.executor().execute(this::clear);
        }

        private void clear() {
            if (cleanup != null) cleanup.cancel(false);
            relays.values().forEach(Relay::close);
            relays.clear();
        }

        private final class Relay {
            private final InetSocketAddress remote;
            private final String ufrag;
            private final ChannelFuture binding;
            private long lastActivity = System.nanoTime();
            private int pending;
            private boolean closed;

            private Relay(InetSocketAddress remote, String ufrag, InetSocketAddress backend) {
                this.remote = remote;
                this.ufrag = ufrag;
                binding = new Bootstrap().group(context.channel().eventLoop()).channel(channelClass)
                        .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                                lastActivity = System.nanoTime();
                                if (!closed && authorizedUfrags.contains(ufrag)) {
                                    // Write below RakNet's handlers: the native packet is already framed and encrypted.
                                    context.writeAndFlush(new DatagramPacket(packet.content().retain(), remote));
                                }
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                close();
                                relays.remove(remote, Relay.this);
                            }
                        })
                        .localAddress(LOOPBACK, 0).connect(backend);
            }

            private void send(ByteBuf data) {
                lastActivity = System.nanoTime();
                if (closed || pending >= 32) {
                    data.release();
                    return;
                }
                pending++;
                binding.addListener(future -> {
                    pending--;
                    if (future.isSuccess() && !closed && authorizedUfrags.contains(ufrag)) {
                        binding.channel().writeAndFlush(data);
                    } else {
                        data.release();
                        close();
                        relays.remove(remote, this);
                    }
                });
            }

            private void close() {
                closed = true;
                binding.channel().close();
            }
        }
    }
}
