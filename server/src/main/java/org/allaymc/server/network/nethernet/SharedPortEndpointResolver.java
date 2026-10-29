package org.allaymc.server.network.nethernet;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.util.NetUtil;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Infers the shared UDP endpoint from an authenticated HTTP join's Host header.
 * Resolves names on a bounded worker pool, caches DNS answers, and never blocks
 * the network event loop. Explicit operator-advertised addresses take precedence.
 */
public final class SharedPortEndpointResolver implements AutoCloseable {
    @FunctionalInterface
    interface Lookup {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    private final int mainPort;
    private final boolean sharedSignalingPort;
    private final boolean explicitAddresses;
    private final ThreadPoolExecutor executor;
    private final AsyncLoadingCache<String, List<InetAddress>> addresses;
    private final Cache<String, CompletableFuture<List<InetSocketAddress>>> pending;
    private final Duration timeout;

    public SharedPortEndpointResolver(int mainPort, int signalingPort, boolean explicitAddresses) {
        this(mainPort, signalingPort, explicitAddresses, host -> List.of(InetAddress.getAllByName(host)),
                Duration.ofSeconds(2));
    }

    SharedPortEndpointResolver(int mainPort, int signalingPort, boolean explicitAddresses,
                               Lookup lookup, Duration timeout) {
        this.mainPort = mainPort;
        this.sharedSignalingPort = mainPort == signalingPort;
        this.explicitAddresses = explicitAddresses;
        this.timeout = timeout;
        var threads = new ThreadFactoryBuilder().setNameFormat("NetherNet address lookup #%d").setDaemon(true).build();
        executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), threads);
        executor.allowCoreThreadTimeOut(true);
        addresses = Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(5))
                .executor(executor).buildAsync(lookup::resolve);
        pending = Caffeine.newBuilder().maximumSize(256).expireAfterWrite(Duration.ofMinutes(1)).build();
    }

    /** Prepares inference after identity validation, before the native peer is created. */
    public void prepare(String networkId, String authority) {
        if (explicitAddresses) return;
        Host host = parse(authority);
        if (host == null) return;
        int port = sharedSignalingPort && host.port() != -1 ? host.port() : mainPort;
        CompletableFuture<List<InetAddress>> resolved;
        InetAddress literal = NetUtil.createInetAddressFromIpAddressString(host.name());
        try {
            resolved = literal == null ? addresses.get(host.name())
                    : CompletableFuture.completedFuture(List.of(literal));
        } catch (RuntimeException unavailable) {
            resolved = CompletableFuture.completedFuture(List.of());
        }
        // The timeout belongs to this join, not to the cached DNS lookup shared by other joins.
        var endpoints = resolved.thenApply(values -> values.stream()
                .filter(ip -> ip instanceof Inet4Address && EndpointAddress.advertisable(ip, false))
                .map(ip -> new InetSocketAddress(ip, port)).distinct().toList())
                .completeOnTimeout(List.of(), timeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(failure -> List.of());
        pending.put(networkId, endpoints);
    }

    /** Takes the prepared endpoints once; a missing or unusable Host leaves normal host discovery intact. */
    public CompletableFuture<List<InetSocketAddress>> take(String networkId) {
        var result = pending.asMap().remove(networkId);
        return result == null ? CompletableFuture.completedFuture(List.of()) : result;
    }

    private static Host parse(String authority) {
        if (authority == null || authority.isBlank() || authority.length() > 320) return null;
        try {
            URI uri = URI.create("http://" + authority);
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                    || uri.getPort() == 0 || uri.getPort() > 65535) return null;
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            return new Host(host.toLowerCase(Locale.ROOT), uri.getPort());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @Override
    public void close() {
        pending.invalidateAll();
        addresses.synchronous().invalidateAll();
        executor.shutdownNow();
    }

    private record Host(String name, int port) {
    }
}
