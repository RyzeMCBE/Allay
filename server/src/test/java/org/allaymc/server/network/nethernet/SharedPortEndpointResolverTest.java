package org.allaymc.server.network.nethernet;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SharedPortEndpointResolverTest {
    private static final String PUBLIC_IP = "93.184.216.34";

    @Test
    void numericHostUsesTheMainPortWithoutDnsOrExtraConfiguration() throws Exception {
        var lookups = new AtomicInteger();
        try (var resolver = resolver(19132, 19132, host -> {
            lookups.incrementAndGet();
            return List.of();
        })) {
            resolver.prepare("one", PUBLIC_IP + ":19132");
            assertEquals(List.of(new InetSocketAddress(PUBLIC_IP, 19132)), resolver.take("one").get());
            resolver.prepare("two", PUBLIC_IP);
            assertEquals(List.of(new InetSocketAddress(PUBLIC_IP, 19132)), resolver.take("two").get());
            assertEquals(0, lookups.get());
        }
    }

    @Test
    void resolvesDomainsOffTheCallerAndCachesTheirAddressesAcrossPublicPorts() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lookups = new AtomicInteger();
        try (var resolver = resolver(19132, 19132, host -> {
            lookups.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(4, TimeUnit.SECONDS)) throw new AssertionError("DNS was not released");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            return List.of(InetAddress.getByName(PUBLIC_IP));
        })) {
            CompletableFuture.runAsync(() -> resolver.prepare("first", "play.example.test:19132")).get(1, TimeUnit.SECONDS);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var first = resolver.take("first");
            assertFalse(first.isDone(), "DNS must not block prepare or finish before the lookup returns");
            resolver.prepare("second", "PLAY.EXAMPLE.TEST:19199");
            var second = resolver.take("second");
            release.countDown();
            assertEquals(List.of(new InetSocketAddress(PUBLIC_IP, 19132)), first.get(2, TimeUnit.SECONDS));
            assertEquals(List.of(new InetSocketAddress(PUBLIC_IP, 19199)), second.get(2, TimeUnit.SECONDS));
            assertEquals(1, lookups.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void separateSignalingDoesNotAdvertiseItsHttpPortAsTheGamePort() throws Exception {
        try (var resolver = resolver(19132, 8443, host -> List.of(InetAddress.getByName(PUBLIC_IP)))) {
            resolver.prepare("one", "play.example.test:443");
            assertEquals(List.of(new InetSocketAddress(PUBLIC_IP, 19132)), resolver.take("one").get());
        }
    }

    @Test
    void invalidAuthoritiesAndUnusableNumericAddressesLeaveHostDiscoveryIntact() throws Exception {
        var lookups = new AtomicInteger();
        try (var resolver = resolver(19132, 19132, host -> {
            lookups.incrementAndGet();
            return List.of(InetAddress.getByName(PUBLIC_IP));
        })) {
            for (String authority : List.of("", "play.example.test/path", "user@play.example.test", "play.example.test?query",
                    "play.example.test#fragment", "play.example.test:0", "play.example.test:65536", "0.0.0.0:19132",
                    "127.0.0.1:19132", "169.254.1.1:19132", "224.0.0.1:19132")) {
                resolver.prepare(authority, authority);
                assertTrue(resolver.take(authority).get().isEmpty(), authority);
            }
            assertEquals(0, lookups.get());
        }
    }

    @Test
    void aSlowJoinDoesNotPoisonTheSharedDnsCache() throws Exception {
        var release = new CountDownLatch(1);
        var lookups = new AtomicInteger();
        try (var resolver = new SharedPortEndpointResolver(19132, 19132, false, host -> {
            lookups.incrementAndGet();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("DNS was not released");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            return List.of(InetAddress.getByName(PUBLIC_IP));
        }, Duration.ofMillis(500))) {
            resolver.prepare("first", "play.example.test");
            assertTrue(resolver.take("first").get(2, TimeUnit.SECONDS).isEmpty());
            release.countDown();
            resolver.prepare("second", "play.example.test");
            assertEquals(List.of(new InetSocketAddress(PUBLIC_IP, 19132)), resolver.take("second").get(2, TimeUnit.SECONDS));
            assertEquals(1, lookups.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void explicitOperatorAddressesTakePrecedenceOverTheJoinHost() throws Exception {
        var lookups = new AtomicInteger();
        try (var resolver = new SharedPortEndpointResolver(19132, 19132, true, host -> {
            lookups.incrementAndGet();
            return List.of(InetAddress.getByName(PUBLIC_IP));
        }, Duration.ofSeconds(2))) {
            resolver.prepare("one", "play.example.test");
            assertTrue(resolver.take("one").get().isEmpty());
            assertEquals(0, lookups.get());
        }
    }

    private static SharedPortEndpointResolver resolver(int mainPort, int signalingPort,
                                                      SharedPortEndpointResolver.Lookup lookup) {
        return new SharedPortEndpointResolver(mainPort, signalingPort, false, lookup, Duration.ofSeconds(5));
    }
}
