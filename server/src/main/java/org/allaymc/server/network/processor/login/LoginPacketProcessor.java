package org.allaymc.server.network.processor.login;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.message.TrKeys;
import org.allaymc.api.player.Player;
import org.allaymc.api.player.Skin;
import org.allaymc.api.server.Server;
import org.allaymc.server.AllayServer;
import org.allaymc.server.network.processor.ingame.ILoginPacketProcessor;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.allaymc.server.network.protocol.ClientVariant;
import org.allaymc.server.player.AllayLoginData;
import org.allaymc.server.player.AllayPlayer;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketType;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import java.util.regex.Pattern;

/**
 * @author daoge_cmd
 */
@Slf4j
public class LoginPacketProcessor extends ILoginPacketProcessor<LoginPacket> {

    public static final Pattern NAME_PATTERN = Pattern.compile("^(?! )([a-zA-Z0-9_ ]{2,15}[a-zA-Z0-9_])(?<! )$");

    @Override
    public void handle(Player player, LoginPacket packet) {
        var allayPlayer = (AllayPlayer) player;
        var loginData = decodeLoginData(packet);
        if (loginData == null) {
            log.warn("Failed to decode login packet received from {}. The client will be disconnected", player.getSocketAddress());
            player.disconnect();
            return;
        }

        if (!selectV2168Codec(allayPlayer, loginData.getGameVersion())) {
            log.warn("Failed to configure the v2168 codec for client {}", player.getSocketAddress());
            player.disconnect();
            return;
        }

        // NetherNet uses DTLS rather than the Bedrock AES handshake. Before
        // trusting a signed login chain, tie it to the identity proven during
        // the WebRTC signaling handshake to prevent replayed login chains.
        if (allayPlayer.getSourceInterface().isTransportEncrypted()) {
            try {
                var channel = allayPlayer.getSession().getPeer().getChannel();
                var mismatch = TransportIdentityBinding.mismatch(channel,
                        EncryptionUtils.parseKey(loginData.getIdentityPublicKey()));
                if (mismatch != null) {
                    log.warn("Rejected unbound NetherNet login from {}: {}", player.getSocketAddress(), mismatch);
                    player.disconnect(TrKeys.MC_DISCONNECTIONSCREEN_NOTAUTHENTICATED);
                    return;
                }
            } catch (Exception exception) {
                log.warn("Failed to verify NetherNet transport identity from {}", player.getSocketAddress(), exception);
                player.disconnect(TrKeys.MC_DISCONNECTIONSCREEN_NOTAUTHENTICATED);
                return;
            }
        }

        allayPlayer.setLoginData(loginData);

        var server = Server.getInstance();
        if (AllayServer.getSettings().genericSettings().enableWhitelist() && !server.getPlayerManager().isWhitelisted(player.getOriginName())) {
            player.disconnect(TrKeys.MC_DISCONNECTIONSCREEN_NOTALLOWED);
            return;
        }

        if (server.getPlayerManager().isBanned(player.getLoginData().getUuid().toString()) || server.getPlayerManager().isBanned(player.getOriginName())) {
            // TODO: I18n
            player.disconnect("You are banned!");
            return;
        }

        if (!loginData.isAuthed() && AllayServer.getSettings().networkSettings().xboxAuth()) {
            player.disconnect(TrKeys.MC_DISCONNECTIONSCREEN_NOTAUTHENTICATED);
            return;
        }

        var name = loginData.getXname();
        if (!isValidName(name)) {
            player.disconnect(TrKeys.MC_DISCONNECTIONSCREEN_INVALIDNAME);
            return;
        }

        if (!loginData.getSkin().isValid()) {
            log.warn("[SKIN-LOGIN-DIAG] Rejected skin for {} (protocol={}): {}", name,
                    allayPlayer.getProtocol().getProtocolVersion(), skinValidationSummary(loginData.getSkin()));
            player.disconnect(TrKeys.MC_DISCONNECTIONSCREEN_INVALIDSKIN);
            return;
        }

        var otherPlayer = server.getPlayerManager().getPlayers().get(loginData.getUuid());
        if (otherPlayer != null) {
            if (otherPlayer.getLoginData().getDeviceInfo().equals(loginData.getDeviceInfo())) {
                otherPlayer.disconnect(TrKeys.MC_DISCONNECTIONSCREEN_LOGGEDINOTHERLOCATION);
            } else {
                player.disconnect(TrKeys.MC_DISCONNECTIONSCREEN_LOGGEDINOTHERLOCATION);
                return;
            }
        }

        // DTLS is already active for NetherNet; its clients have no RakNet
        // AES handshake to answer, so complete login after identity validation.
        if (allayPlayer.getSourceInterface().isTransportEncrypted()
                || !AllayServer.getSettings().networkSettings().enableNetworkEncryption()) {
            allayPlayer.completeLogin();
            return;
        }

        try {
            allayPlayer.setNetworkEncryptionEnabled(true);

            var clientKey = EncryptionUtils.parseKey(loginData.getIdentityPublicKey());
            var serverKeyPair = EncryptionUtils.createKeyPair();
            var token = EncryptionUtils.generateRandomToken();

            var handshakePacket = new ServerToClientHandshakePacket();
            handshakePacket.setJwt(EncryptionUtils.createHandshakeJwt(serverKeyPair, token));
            allayPlayer.sendPacketImmediately(handshakePacket);

            var encryptionSecretKey = EncryptionUtils.getSecretKey(serverKeyPair.getPrivate(), clientKey, token);
            allayPlayer.getSession().enableEncryption(encryptionSecretKey);
            // completeLogin() when client send back ClientToServerHandshakePacket
        } catch (Exception exception) {
            log.warn("Failed to initialize encryption for client {}", name, exception);
            player.disconnect("disconnectionScreen.internalError");
        }
    }

    /**
     * Decodes login data for the international protocol branch.
     *
     * @param packet the login packet
     * @return decoded login data, or {@code null} when decoding fails
     */
    protected AllayLoginData decodeLoginData(LoginPacket packet) {
        return AllayLoginData.decode(packet, false);
    }

    boolean selectV2168Codec(AllayPlayer player, String minecraftVersion) {
        var protocol = player.getProtocol();
        if (protocol.getVariant() != ClientVariant.INTERNATIONAL
                || protocol.getProtocolVersion() != Bedrock_v2168.CODEC.getProtocolVersion()
                || !isV2168BaseVersion(minecraftVersion)) {
            return true;
        }

        var protocolSession = player.getProtocolSession();
        if (protocolSession == null || !player.getSession().isConnected()) {
            return false;
        }
        protocolSession.switchCodec(Bedrock_v2168.CODEC);
        return true;
    }

    private static boolean isV2168BaseVersion(String minecraftVersion) {
        var baseVersion = Bedrock_v2168.CODEC.getMinecraftVersion();
        return minecraftVersion != null && (minecraftVersion.equals(baseVersion) || minecraftVersion.startsWith(baseVersion + "."));
    }

    /**
     * Validates an international player name.
     *
     * @param name the decoded player name
     * @return whether the name is valid for this protocol branch
     */
    protected boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    private static String skinValidationSummary(Skin skin) {
        var image = skin.skinData();
        var imageBytes = image == null || image.data() == null ? 0 : image.data().length;
        return "skinIdPresent=" + (skin.skinId() != null && !skin.skinId().trim().isEmpty())
                + " image=" + (image == null ? "missing" : image.width() + "x" + image.height())
                + " imageBytes=" + imageBytes
                + " expectedImageBytes=" + (image == null ? 0L : (long) image.width() * image.height() * 4)
                + " imageValid=" + (image != null && image.data() != null && image.isValid())
                + " persona=" + skin.personaSkin()
                + " resourcePatch=" + resourcePatchStatus(skin.skinResourcePatch());
    }

    private static String resourcePatchStatus(String patch) {
        if (patch == null) return "missing";
        try {
            var root = JsonParser.parseString(patch);
            if (!root.isJsonObject()) return "not_object";
            var geometry = root.getAsJsonObject().get("geometry");
            if (geometry == null) return "geometry_missing";
            if (!geometry.isJsonObject()) return "geometry_not_object";
            var defaultGeometry = geometry.getAsJsonObject().get("default");
            if (defaultGeometry == null) return "default_missing";
            if (!defaultGeometry.isJsonPrimitive() || !defaultGeometry.getAsJsonPrimitive().isString()) {
                return "default_not_string";
            }
            return "valid";
        } catch (JsonParseException exception) {
            return "invalid_json";
        }
    }

    @Override
    public BedrockPacketType getPacketType() {
        return BedrockPacketType.LOGIN;
    }
}
