package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.player.Player;
import org.allaymc.server.network.processor.PacketProcessor;
import org.allaymc.server.player.AllayPlayer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketType;
import org.cloudburstmc.protocol.bedrock.packet.PacketSignal;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;

/**
 * Handles client loading screen lifecycle packets on the network thread.
 * Does not stall chunk publishing while waiting for END_LOADING_SCREEN.
 */
public class ServerboundLoadingScreenPacketProcessor extends PacketProcessor<ServerboundLoadingScreenPacket> {
    @Override
    public PacketSignal handleAsync(Player player, ServerboundLoadingScreenPacket packet, long receiveTime) {
        ((AllayPlayer) player).onClientDimensionLoadingScreen(packet);
        return PacketSignal.HANDLED;
    }

    @Override
    public BedrockPacketType getPacketType() {
        return BedrockPacketType.SERVERBOUND_LOADING_SCREEN;
    }
}
