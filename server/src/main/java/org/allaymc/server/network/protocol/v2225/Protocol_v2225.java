package org.allaymc.server.network.protocol.v2225;

import org.allaymc.api.player.ClientState;
import org.allaymc.server.network.processor.PacketProcessorRegistry;
import org.allaymc.server.network.processor.common.NoopPacketProcessor;
import org.allaymc.server.network.processor.ingame.StonecutterSetRecipePacketProcessor;
import org.allaymc.server.network.protocol.ClientVariant;
import org.allaymc.server.network.protocol.PacketEncoder;
import org.allaymc.server.network.protocol.ProtocolData;
import org.allaymc.server.network.protocol.v2193.Protocol_v2193;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketType;

/** Support for Minecraft Bedrock 1.26.60, protocol 2225. */
public class Protocol_v2225 extends Protocol_v2193 {
    public Protocol_v2225() {
        super(Bedrock_v2225.CODEC, ClientVariant.INTERNATIONAL);
    }

    @Override
    protected String getBlockPaletteResource() {
        return "protocol_palettes/1_26_60.nbt";
    }

    @Override
    protected void registerProcessors(PacketProcessorRegistry registry) {
        super.registerProcessors(registry);
        registry.register(ClientState.IN_GAME, BedrockPacketType.SERVERBOUND_STONECUTTER_SET_RECIPE,
                StonecutterSetRecipePacketProcessor::new);
        for (var type : new BedrockPacketType[]{
                BedrockPacketType.SERVERBOUND_CURSOR_ITEM_DRAG,
                BedrockPacketType.SERVERBOUND_MATCHMAKING_CANCEL,
                BedrockPacketType.SERVERBOUND_REGISTER_AUDIO_CONTENT,
                BedrockPacketType.SET_PASSENGER_OF_BLOCK
        }) {
            registry.register(ClientState.IN_GAME, type, () -> new NoopPacketProcessor(type));
        }
    }

    @Override
    protected PacketEncoder createEncoder(ProtocolData data) {
        return new PacketEncoder_v2225(data);
    }
}
