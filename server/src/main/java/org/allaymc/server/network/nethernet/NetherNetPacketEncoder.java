package org.allaymc.server.network.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.util.VarInts;

import java.util.List;

/**
 * Produce one Bedrock batch per NetherNet message instead of coalescing messages
 * across a Netty flush. NetherNet handles reliable ordered fragmentation itself.
 */
@ChannelHandler.Sharable
public final class NetherNetPacketEncoder extends MessageToMessageEncoder<BedrockPacketWrapper> {
    public static final String NAME = "nethernet-packet-encoder";
    public static final NetherNetPacketEncoder INSTANCE = new NetherNetPacketEncoder();

    private NetherNetPacketEncoder() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockPacketWrapper packet, List<Object> out) {
        ByteBuf payload = packet.getPacketBuffer();
        if (payload == null) {
            throw new IllegalArgumentException("Bedrock packet not encoded");
        }
        CompositeByteBuf composite = ctx.alloc().compositeDirectBuffer(2);
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
        try {
            ByteBuf header = ctx.alloc().ioBuffer(5);
            VarInts.writeUnsignedInt(header, payload.readableBytes());
            composite.addComponent(true, header);
            composite.addComponent(true, payload.retain());
            batch.addPacket(packet.retain());
            batch.setUncompressed(composite.retain());
            out.add(batch.retain());
        } finally {
            composite.release();
            batch.release();
        }
    }
}
