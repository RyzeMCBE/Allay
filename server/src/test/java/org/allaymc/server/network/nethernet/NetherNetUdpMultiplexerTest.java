package org.allaymc.server.network.nethernet;

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetUdpMultiplexerTest {
    @Test
    void distinguishesStunAndDtlsFromRaknetEvenWithReaderOffsets() {
        var stun = Unpooled.buffer().writeInt(123).writeShort(1).writeShort(0)
                .writeInt(0x2112a442).writeZero(12).readerIndex(4);
        var dtls = Unpooled.buffer().writeByte(22).writeByte(0xfe).writeByte(0xfd).writeZero(10);
        var ping = Unpooled.buffer().writeByte(1).writeLong(0).writeZero(24);
        var frame = Unpooled.buffer().writeByte(0x84).writeZero(100);
        try {
            assertTrue(NetherNetUdpMultiplexer.isStun(stun));
            assertTrue(NetherNetUdpMultiplexer.isDtls(dtls));
            assertFalse(NetherNetUdpMultiplexer.isStun(ping));
            assertFalse(NetherNetUdpMultiplexer.isDtls(ping));
            assertFalse(NetherNetUdpMultiplexer.isStun(frame));
            assertFalse(NetherNetUdpMultiplexer.isDtls(frame));
            stun.setShort(6, 4);
            assertFalse(NetherNetUdpMultiplexer.isStun(stun));
        } finally {
            stun.release();
            dtls.release();
            ping.release();
            frame.release();
        }
    }

    @Test
    void rejectsTruncatedUsernamesAndExtractsTheReceivingPeersUfrag() {
        var request = Unpooled.buffer().writeShort(1).writeShort(16).writeInt(0x2112a442).writeZero(12)
                .writeShort(6).writeShort(12);
        request.writeCharSequence("server:guest", StandardCharsets.US_ASCII);
        try {
            assertEquals("server", NetherNetUdpMultiplexer.requestedUfrag(request));
            request.setShort(22, 100);
            assertNull(NetherNetUdpMultiplexer.requestedUfrag(request));
        } finally {
            request.release();
        }
    }

    @Test
    void proxyHeaderPreservesOriginalAddressesPortsAndPayloadForBothIpFamilies() {
        for (String sourceHost : List.of("198.51.100.7", "2001:db8::7")) {
            var source = new InetSocketAddress(sourceHost, 54321);
            var destination = new InetSocketAddress(sourceHost.contains(":") ? "2001:db8::1" : "192.0.2.1", 19132);
            var payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4});
            var packet = NetherNetUdpMultiplexer.proxyPacket(UnpooledByteBufAllocator.DEFAULT,
                    source, destination, payload);
            try {
                assertEquals(0x21, packet.getUnsignedByte(12));
                assertEquals(sourceHost.contains(":") ? 0x22 : 0x12, packet.getUnsignedByte(13));
                int size = source.getAddress().getAddress().length;
                byte[] actualSource = new byte[size];
                packet.getBytes(16, actualSource);
                assertArrayEquals(source.getAddress().getAddress(), actualSource);
                assertEquals(54321, packet.getUnsignedShort(16 + size * 2));
                assertEquals(19132, packet.getUnsignedShort(18 + size * 2));
                assertEquals(0x01020304, packet.getInt(20 + size * 2));
                assertEquals(0, payload.readerIndex());
            } finally {
                packet.release();
                payload.release();
            }
        }
    }

    @Test
    void advertisesOnlyThePublicPortAndStillAllowsHttpNatInference() {
        String sdp = "v=0\r\na=ice-ufrag:server\r\na=identity:unchanged\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
                + "a=candidate:1 1 UDP 2122260223 127.0.0.1 54000 typ host\r\n"
                + "a=end-of-candidates\r\n";
        String announced = SharedPortNetherNetSignaling.publicDescription(sdp,
                List.of(new InetSocketAddress("172.18.0.2", 19132)));
        assertTrue(announced.contains("172.18.0.2 19132 typ host"));
        assertFalse(announced.contains("54000"));
        assertFalse(announced.contains("127.0.0.1"));
        assertTrue(announced.contains("a=ice-ufrag:server\r\n"));
        assertTrue(announced.contains("a=identity:unchanged\r\n"));
        String behindNat = SdpUtil.withAddedEndpoints(announced,
                List.of(new InetSocketAddress("198.51.100.9", 19132)));
        assertTrue(behindNat.contains("198.51.100.9 19132 typ srflx"));
    }

}
