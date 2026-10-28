package org.allaymc.server.network.protocol.v2225;

import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.player.Player;
import org.allaymc.server.network.protocol.ProtocolData;
import org.allaymc.server.network.protocol.v2193.PacketEncoder_v2193;
import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.bedrock.packet.*;
import java.util.Collection;

/** Packet layouts and skin trust semantics for protocol 2225. */
public class PacketEncoder_v2225 extends PacketEncoder_v2193 {
    public PacketEncoder_v2225(ProtocolData data) {
        super(data);
    }

    @Override
    public DimensionDataPacket encodeDimensionData() {
        var packet = super.encodeDimensionData();
        for (int i = 0; i < packet.getDefinitions().size(); i++) {
            var definition = packet.getDefinitions().get(i);
            var overworld = "minecraft:overworld".equals(definition.id());
            packet.getDefinitions().set(i, new DimensionDefinition(
                    definition.id(), definition.maximumHeight(), definition.minimumHeight(),
                    definition.generatorType(), definition.dimensionType(), definition.packId(),
                    definition.defaultBiome(), 128, overworld));
        }
        return packet;
    }

    @Override
    public PlayerListPacket encodePlayerList(Collection<? extends Player> players, boolean add, boolean trusted) {
        var packet = super.encodePlayerList(players, add, trusted);
        applySkinTrust(packet, trusted);
        return packet;
    }

    @Override
    public Collection<BedrockPacket> encodePlayerSkin(EntityPlayer player, boolean trusted) {
        var packets = super.encodePlayerSkin(player, trusted);
        for (var packet : packets) {
            if (packet instanceof PlayerListPacket list) {
                applySkinTrust(list, trusted);
            } else if (packet instanceof PlayerSkinPacket skin) {
                skin.setSkin(skin.getSkin().toBuilder().trusted(trusted).build());
            }
        }
        return packets;
    }

    private static void applySkinTrust(PlayerListPacket packet, boolean trusted) {
        for (var entry : packet.getEntries()) {
            if (entry.getSkin() != null) {
                entry.setSkin(entry.getSkin().toBuilder().trusted(trusted).build());
            }
        }
    }

    public ClientboundStonecutterSetRecipePacket encodeStonecutterSelection(long playerId, int containerId, int recipeIndex) {
        var packet = new ClientboundStonecutterSetRecipePacket();
        packet.setPlayerUniqueEntityId(playerId);
        packet.setContainerId(containerId);
        packet.setRecipeIndex(recipeIndex);
        return packet;
    }
}
