package org.allaymc.server.network.protocol.v2168;

import org.allaymc.api.player.Player;
import org.allaymc.server.network.protocol.ProtocolData;
import org.allaymc.server.network.protocol.v1001.PacketEncoder_v1001;
import org.cloudburstmc.protocol.bedrock.data.BuildPlatform;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;

import java.util.Collection;

public class PacketEncoder_v2168 extends PacketEncoder_v1001 {
    public PacketEncoder_v2168(ProtocolData data) {
        super(data);
    }

    @Override
    public PlayerListPacket encodePlayerList(Collection<? extends Player> players, boolean add, boolean trustSkins) {
        var packet = super.encodePlayerList(players, add, trustSkins);
        for (var entry : packet.getEntries()) {
            // Cereal's modern BuildPlatform enum represents Windows as Win32, without a UWP value.
            if (entry.getBuildPlatform() == BuildPlatform.UWP) {
                entry.setBuildPlatform(BuildPlatform.WIN32);
            }
        }
        return packet;
    }
}
