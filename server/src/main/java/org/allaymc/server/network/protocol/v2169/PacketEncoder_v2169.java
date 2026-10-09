package org.allaymc.server.network.protocol.v2169;

import org.allaymc.server.network.protocol.ProtocolData;
import org.allaymc.server.network.protocol.v2168.PacketEncoder_v2168;

/**
 * Encoder for protocol 2169. It inherits the compatible 2168 packet encoding
 * until protocol-specific serializer differences are required.
 */
public class PacketEncoder_v2169 extends PacketEncoder_v2168 {

    public PacketEncoder_v2169(ProtocolData data) {
        super(data);
    }
}
