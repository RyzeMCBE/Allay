package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.container.interfaces.StonecutterContainer;
import org.allaymc.api.item.recipe.ShapelessRecipe;
import org.allaymc.api.player.Player;
import org.allaymc.server.network.processor.PacketProcessor;
import org.allaymc.server.network.protocol.v2225.PacketEncoder_v2225;
import org.allaymc.server.player.AllayPlayer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketType;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundStonecutterSetRecipePacket;

/** Validates a 2225 stonecutter selection against the open container and known recipe table. */
public final class StonecutterSetRecipePacketProcessor extends PacketProcessor<ServerboundStonecutterSetRecipePacket> {
    @Override
    public void handleSync(Player player, ServerboundStonecutterSetRecipePacket packet, long receiveTime) {
        if (packet.getContainerId() < 0 || packet.getContainerId() > 255) return;
        var opened = player.getOpenedContainer((byte) packet.getContainerId());
        if (!(opened instanceof StonecutterContainer container)) return;
        var allayPlayer = (AllayPlayer) player;
        var recipeInput = container.createRecipeInput();
        var matches = allayPlayer.getProtocol().getData().recipeTable().recipesByNetworkId().values().stream()
                .filter(recipe -> recipe instanceof ShapelessRecipe shapeless &&
                        shapeless.getType() == ShapelessRecipe.Type.STONECUTTER &&
                        recipe.match(recipeInput))
                .count();
        var index = packet.getRecipeIndex();
        if (index < -1 || index >= matches) index = -1;
        var encoder = (PacketEncoder_v2225) allayPlayer.getProtocol().getEncoder();
        var response = encoder.encodeStonecutterSelection(
                player.getLoginData().getUuid().getLeastSignificantBits(), packet.getContainerId(), index);
        allayPlayer.getProtocolSession().send(allayPlayer, response);
    }

    @Override
    public BedrockPacketType getPacketType() {
        return BedrockPacketType.SERVERBOUND_STONECUTTER_SET_RECIPE;
    }
}
