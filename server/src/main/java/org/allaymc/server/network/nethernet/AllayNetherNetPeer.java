package org.allaymc.server.network.nethernet;

import io.netty.channel.Channel;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSessionFactory;

import javax.crypto.SecretKey;

/**
 * Reuses Bedrock session and codec machinery over a DTLS WebRTC data channel.
 */
public final class AllayNetherNetPeer extends BedrockPeer {
    public AllayNetherNetPeer(Channel channel, BedrockSessionFactory sessionFactory) {
        super(channel, sessionFactory);
    }

    @Override
    public int getRakVersion() {
        // NetherNet's negotiated Bedrock compression semantics match RakNet v11.
        return 11;
    }

    @Override
    public void enableEncryption(SecretKey key) {
        // NetherNet is already encrypted by DTLS. The Bedrock AES handshake is not used.
    }
}
