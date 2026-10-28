package org.allaymc.server.network.nethernet;

import io.netty.channel.EventLoop;
import org.cloudburstmc.netty.channel.nethernet.config.NetherServerMetrics;
import org.cloudburstmc.netty.channel.nethernet.signaling.IceServerInfo;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keeps HTTP authentication intact while advertising the shared UDP socket instead
 * of the native transport's private endpoint. Relay admission follows the lifetime
 * of the authenticated NetherNet peer, not the HTTP connection.
 */
public final class SharedPortNetherNetSignaling implements NetherNetServerSignaling {
    private final NetherNetHTTPServerSignaling delegate;
    private final NetherNetUdpMultiplexer multiplexer;
    private final InetSocketAddress publicAddress;
    private final List<InetSocketAddress> hostEndpoints;
    private final Map<String, Join> joins = new HashMap<>();
    private final Map<String, Join> pending = new HashMap<>();

    public SharedPortNetherNetSignaling(NetherNetHTTPServerSignaling delegate,
                                      NetherNetUdpMultiplexer multiplexer,
                                      InetSocketAddress publicAddress, List<InetSocketAddress> hostEndpoints) {
        this.delegate = delegate;
        this.multiplexer = multiplexer;
        this.publicAddress = publicAddress;
        this.hostEndpoints = List.copyOf(hostEndpoints);
    }

    @Override
    public void bind(SocketAddress localAddress, InetSocketAddress iceAddress, EventLoop eventLoop)
            throws ConnectException {
        // HTTP's Host-header NAT inference must see the public port, never the private native one.
        delegate.bind(localAddress, publicAddress, eventLoop);
    }

    @Override
    public void setMediaRelayed(boolean relayed) {
        // We replace native candidates ourselves; HTTP can still infer the endpoint the player typed.
    }

    @Override
    public void setNewConnectionHandler(NewConnectionHandler handler) {
        delegate.setNewConnectionHandler((connectionId, networkId, offer, address, player) -> {
            synchronized (this) {
                var join = new Join(networkId);
                joins.put(connectionId, join);
                pending.put(networkId, join);
            }
            handler.onConnect(connectionId, networkId, offer, address, player);
        });
    }

    @Override
    public void sendDescription(String targetNetworkId, String sdp) {
        String ufrag = sdp.lines().filter(line -> line.startsWith("a=ice-ufrag:"))
                .map(line -> line.substring("a=ice-ufrag:".length())).findFirst().orElseThrow();
        synchronized (this) {
            var join = pending.remove(targetNetworkId);
            if (join != null) {
                join.ufrag = ufrag;
                multiplexer.authorize(ufrag);
            }
        }
        delegate.sendDescription(targetNetworkId, publicDescription(sdp, hostEndpoints));
    }

    static String publicDescription(String sdp, List<InetSocketAddress> endpoints) {
        // These are host candidates of the front socket. Keeping their host type lets HTTP add a
        // public NAT mapping from Host, or honor an explicit advertised-address configuration.
        return SdpUtil.withRelayedCandidates(sdp, endpoints).lines().map(line -> {
            if (!line.startsWith("a=candidate:")) return line;
            String[] fields = line.split(" ");
            fields[7] = "host";
            return String.join(" ", Arrays.copyOf(fields, 8));
        }).collect(Collectors.joining("\r\n", "", "\r\n"));
    }

    @Override
    public synchronized void removeSignalHandler(String connectionId) {
        var join = joins.remove(connectionId);
        if (join != null) {
            pending.remove(join.networkId, join);
            if (join.ufrag != null) multiplexer.revoke(join.ufrag);
        }
        delegate.removeSignalHandler(connectionId);
    }

    @Override
    public void setSignalHandler(String connectionId, SignalHandler handler) {
        delegate.setSignalHandler(connectionId, handler);
    }

    @Override
    public boolean usesTrickleIce() {
        return false;
    }

    @Override
    public List<IceServerInfo> getIceServers() {
        return delegate.getIceServers();
    }

    @Override
    public boolean allowsIceOnLocalPort() {
        return false;
    }

    @Override
    public String getLocalNetworkId() {
        return delegate.getLocalNetworkId();
    }

    @Override
    public boolean isActive() {
        return delegate.isActive();
    }

    @Override
    public OperatorIdentity serverIdentity() {
        return delegate.serverIdentity();
    }

    @Override
    public void setAdvertisementData(PongData data) {
        delegate.setAdvertisementData(data);
    }

    @Override
    public void setMetrics(NetherServerMetrics metrics) {
        delegate.setMetrics(metrics);
    }

    @Override
    public synchronized void close() {
        delegate.close();
        for (var join : joins.values()) if (join.ufrag != null) multiplexer.revoke(join.ufrag);
        joins.clear();
        pending.clear();
    }

    private static final class Join {
        private final String networkId;
        private String ufrag;

        private Join(String networkId) {
            this.networkId = networkId;
        }
    }
}
