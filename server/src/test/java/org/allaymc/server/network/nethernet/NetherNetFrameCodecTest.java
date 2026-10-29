package org.allaymc.server.network.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetFrameCodecTest {
    @Test
    void omitsLegacyRakNetFrameId() {
        var channel = new EmbeddedChannel(NetherNetFrameCodec.INSTANCE);
        var batch = BedrockBatchWrapper.newInstance();
        batch.setCompressed(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4}));
        try {
            assertTrue(channel.writeOutbound(batch.retain()));
            ByteBuf result = channel.readOutbound();
            try {
                assertEquals(4, result.readableBytes());
                assertEquals(1, result.readUnsignedByte()); // not RakNet's 0xFE marker
                assertEquals(2, result.readUnsignedByte());
            } finally {
                result.release();
            }
        } finally {
            batch.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void acceptsNetherNetMessageWithoutRakNetHeader() {
        var channel = new EmbeddedChannel(NetherNetFrameCodec.INSTANCE);
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{1, 2, 3})));
            BedrockBatchWrapper result = channel.readInbound();
            try {
                assertNotNull(result.getCompressed());
                assertEquals(3, result.getCompressed().readableBytes());
                assertEquals(1, result.getCompressed().readUnsignedByte());
            } finally {
                result.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
