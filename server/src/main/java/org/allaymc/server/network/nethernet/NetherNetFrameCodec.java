package org.allaymc.server.network.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;

import java.util.List;

/**
 * Each NetherNet data channel message is exactly one compressed Bedrock batch.
 * Unlike RakNet, WebRTC does not prepend the 0xFE framing byte.
 */
@ChannelHandler.Sharable
public final class NetherNetFrameCodec extends MessageToMessageCodec<ByteBuf, BedrockBatchWrapper> {
    public static final String NAME = "nethernet-frame";
    public static final NetherNetFrameCodec INSTANCE = new NetherNetFrameCodec();

    private NetherNetFrameCodec() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper batch, List<Object> out) {
        if (batch.getCompressed() == null) {
            throw new IllegalStateException("NetherNet Bedrock batch is not compressed");
        }
        out.add(batch.getCompressed().retainedSlice());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf data, List<Object> out) {
        if (data.isReadable()) {
            out.add(BedrockBatchWrapper.newInstance(data.readRetainedSlice(data.readableBytes()), null));
        }
    }
}
