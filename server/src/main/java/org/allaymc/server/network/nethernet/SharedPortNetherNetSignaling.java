package org.allaymc.server.network.nethernet;

import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;
import org.cloudburstmc.netty.channel.nethernet.config.NetherServerMetrics;
import org.cloudburstmc.netty.channel.nethernet.signaling.IceServerInfo;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Keeps HTTP authentication intact while advertising the shared UDP socket instead
 * of the native transport's private endpoint. Relay admission follows the lifetime
 * of the authenticated NetherNet peer, not the HTTP connection.
 */
@Slf4j
public final class SharedPortNetherNetSignaling implements NetherNetServerSignaling {
    private final NetherNetHTTPServerSignaling delegate;
    private final NetherNetUdpMultiplexer multiplexer;
    private final InetSocketAddress publicAddress;
    private final List<InetSocketAddress> hostEndpoints;
    private final SharedPortEndpointResolver endpointResolver;
    private final Map<String, Join> joins = new HashMap<>();
    private final Map<String, Join> pending = new HashMap<>();

    public SharedPortNetherNetSignaling(NetherNetHTTPServerSignaling delegate,
                                      NetherNetUdpMultiplexer multiplexer,
                                      InetSocketAddress publicAddress, List<InetSocketAddress> hostEndpoints) {
        this(delegate, multiplexer, publicAddress, hostEndpoints, null);
    }

    public SharedPortNetherNetSignaling(NetherNetHTTPServerSignaling delegate,
                                      NetherNetUdpMultiplexer multiplexer,
                                      InetSocketAddress publicAddress, List<InetSocketAddress> hostEndpoints,
                                      SharedPortEndpointResolver endpointResolver) {
        this.delegate = delegate;
        this.multiplexer = multiplexer;
        this.publicAddress = publicAddress;
        this.hostEndpoints = List.copyOf(hostEndpoints);
        this.endpointResolver = endpointResolver;
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
                var join = new Join(connectionId, networkId, endpointResolver == null
                        ? CompletableFuture.completedFuture(List.of()) : endpointResolver.take(networkId));
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
        Join join;
        synchronized (this) {
            join = pending.remove(targetNetworkId);
        }
        if (join == null) return;
        // DNS belongs to a bounded worker pool. The native callback and Netty loops remain free.
        join.endpoints.thenAccept(endpoints -> {
            String announced = SdpUtil.withAddedEndpoints(publicDescription(sdp, hostEndpoints), endpoints);
            long probeId;
            synchronized (this) {
                if (joins.get(join.connectionId) != join) return;
                join.ufrag = ufrag;
                probeId = multiplexer.authorize(ufrag);
            }
            if (probeId > 0) {
                // Only candidate counts are logged; ICE usernames/passwords, signed SDP and
                // authenticated player information are intentionally excluded.
                long candidates = announced.lines().filter(line -> line.startsWith("a=candidate:")).count();
                log.info("NetherNet diagnostic #{}: signed SDP answer ready; candidates={} "
                        + "sharedUdpPort={}", probeId, candidates, publicAddress.getPort());
            }
            logEndpoints(announced);
            delegate.sendDescription(targetNetworkId, announced);
            if (probeId > 0) log.info("NetherNet diagnostic #{}: HTTP signaling answer dispatched", probeId);
        }).exceptionally(failure -> {
            log.warn("Could not complete the NetherNet SDP answer", failure);
            removeSignalHandler(join.connectionId);
            return null;
        });
    }

    private void logEndpoints(String sdp) {
        String finalSdp = delegate.advertisedAddresses().isEmpty() ? sdp
                : SdpUtil.withAdvertisedCandidates(sdp, delegate.advertisedAddresses());
        var endpoints = finalSdp.lines().filter(line -> line.startsWith("a=candidate:"))
                .map(line -> line.split(" ")).filter(parts -> parts.length >= 8)
                .map(parts -> new InetSocketAddress(parts[4], Integer.parseInt(parts[5]))).distinct().toList();
        // Do not log SDP, ICE passwords, identity assertions or client tokens.
        log.info("NetherNet advertised UDP endpoints: {} (shared Bedrock port {})", endpoints, publicAddress.getPort());
        if (endpoints.stream().noneMatch(endpoint -> EndpointAddress.scope(endpoint.getAddress()) == EndpointAddress.Scope.PUBLIC)) {
            log.warn("NetherNet is advertising only LAN/loopback endpoints. An internet client behind NAT may not reach them. "
                    + "If the connection Host does not name the public game endpoint, set nethernet-advertise-addresses "
                    + "to that endpoint on the main UDP port {}. Native relay ports in transport warnings are internal.",
                    publicAddress.getPort());
        }
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
        private final String connectionId;
        private final String networkId;
        private final CompletableFuture<List<InetSocketAddress>> endpoints;
        private String ufrag;

        private Join(String connectionId, String networkId, CompletableFuture<List<InetSocketAddress>> endpoints) {
            this.connectionId = connectionId;
            this.networkId = networkId;
            this.endpoints = endpoints;
        }
    }
}
