package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.player.Player;
import org.allaymc.server.network.processor.PacketProcessor;
import org.allaymc.server.player.AllayPlayer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketType;
import org.cloudburstmc.protocol.bedrock.packet.PacketSignal;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;

/**
 * Observes the Bedrock client's own END_LOADING_SCREEN notification.
 * Does not change the loading screen, dimension ACK, or chunk sending order.
 */
public final class DimensionLoadingProtectionPacketProcessor
        extends PacketProcessor<ServerboundLoadingScreenPacket> {

    @Override
    public PacketSignal handleAsync(Player player, ServerboundLoadingScreenPacket packet, long receiveTime) {
        ((AllayPlayer) player).onTransferLoadingScreenPacket(packet.getType(), packet.getLoadingScreenId());
        return PacketSignal.HANDLED;
    }

    @Override
    public BedrockPacketType getPacketType() {
        return BedrockPacketType.SERVERBOUND_LOADING_SCREEN;
    }
}
