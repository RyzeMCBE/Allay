package org.allaymc.server.player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.container.interfaces.ArmorContainer;
import org.allaymc.api.container.interfaces.InventoryContainer;
import org.allaymc.api.container.interfaces.OffhandContainer;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.entity.type.EntityTypes;
import org.allaymc.api.item.interfaces.ItemAirStack;
import org.allaymc.api.math.location.Location3d;
import org.allaymc.api.player.GameMode;
import org.allaymc.api.player.LoginData;
import org.allaymc.api.player.Player;
import org.allaymc.api.world.Dimension;
import org.allaymc.server.network.protocol.ClientVariant;
import org.allaymc.server.network.protocol.Protocol;
import org.allaymc.server.network.protocol.ProtocolRegistry;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.BuildPlatform;
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.definition.SimpleDefinitionRegistry;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.util.VarInts;
import org.joml.primitives.AABBd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Replays captured appearance data through the real login decoder, packet encoder and all registered codecs.
 * This is not a Minecraft client, a RakNet replay, or an authentication test.
 */
@ExtendWith(AllayTestExtension.class)
class CapturedPlayerPacketReplayTest {
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-8000-000000000003");
    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-8000-000000000004");
    private static final String SOURCE_CAPTURE = "windows-844-party";
    private static final String PERSONA_CAPTURE = "windows-827-persona";

    @Test
    void sourceCaptureRetainsTheActualTextureAndLegacyGeometry() throws Exception {
        var capture = capture(SOURCE_CAPTURE);
        assertEquals(7, capture.get("DeviceOS").getAsInt());
        assertEquals("1.21.114", capture.get("GameVersion").getAsString());
        assertEquals("e49c64a4fcda9dd8d807af7d6adda3ab161c35b336bec31e527d393046730a5b",
                sha256(Base64.getDecoder().decode(capture.get("SkinData").getAsString())));
        assertEquals("a3cac46f7e3abda08c78d5ce44ad2c0d66d971370ee1bed3e75a11436a99f85a",
                sha256(Base64.getDecoder().decode(capture.get("SkinGeometryData").getAsString())));

        var login = decodeAppearance(capture);
        assertEquals(LoginData.Device.WINDOWS, login.getDeviceInfo().device());
        assertTrue(login.getSkin().isValid());
        assertEquals(64, login.getSkin().skinData().width());
        assertEquals(64, login.getSkin().skinData().height());
        assertFalse(login.getSkin().personaSkin());
        assertEquals("1.8.0", JsonParser.parseString(login.getSkin().skinGeometry())
                .getAsJsonObject().get("format_version").getAsString());
    }

    static Stream<Arguments> replayCases() {
        return Stream.of(SOURCE_CAPTURE, PERSONA_CAPTURE).flatMap(source ->
                supportedProtocols().flatMap(protocol -> Stream.of(7, 1).flatMap(device ->
                Stream.of("android-2193-classic", "android-2193-persona").flatMap(recipient ->
                        Stream.of(false, true).map(selfFirst -> Arguments.of(protocol, source, device, recipient, selfFirst))))));
    }

    static Stream<Protocol> supportedProtocols() {
        return ProtocolRegistry.getDefault().getProtocols().stream()
                .sorted(Comparator.comparing((Protocol protocol) -> protocol.getVariant().name())
                        .thenComparingInt(Protocol::getProtocolVersion));
    }

    static Stream<Arguments> windowsPlatformCases() {
        return Stream.of(1001, 2168, 2193).flatMap(version -> Stream.of(SOURCE_CAPTURE, PERSONA_CAPTURE)
                .map(capture -> Arguments.of(version, capture)));
    }

    @ParameterizedTest(name = "Windows player-list platform: protocol={0}, capture={1}")
    @MethodSource("windowsPlatformCases")
    void windowsPlatformMatchesTheRecipientSchemaWithoutChangingTheSkin(int version, String captureName) throws Exception {
        var protocol = ProtocolRegistry.getDefault().resolve(ClientVariant.INTERNATIONAL, version);
        var data = capture(captureName);
        var login = decodeAppearance(data);
        var source = player(login, SOURCE_ID, "SourceFixture", 3);
        var packet = protocol.getEncoder().encodePlayerList(List.of(source.player()), true, true);

        // Read the field directly: a codec round trip also accepts retired enum values.
        var buffer = Unpooled.wrappedBuffer(encode(protocol, packet));
        try {
            if (version >= 2168) {
                assertEquals(1, VarInts.readUnsignedInt(buffer));
                assertEquals(1, VarInts.readUnsignedInt(buffer));
                assertEquals(0, buffer.readUnsignedByte());
            } else {
                assertEquals(0, buffer.readUnsignedByte());
                assertEquals(1, VarInts.readUnsignedInt(buffer));
            }
            buffer.skipBytes(16);
            VarInts.readLong(buffer);
            for (int i = 0; i < 3; i++) {
                buffer.skipBytes(VarInts.readUnsignedInt(buffer));
            }
            // Mojang's 2168/2193 BuildPlatform schema has Win32 (8), but no UWP (7).
            assertEquals(version >= 2168 ? 8 : 7, buffer.readIntLE());
        } finally {
            buffer.release();
        }
        assertEquals(LoginData.Device.WINDOWS, login.getDeviceInfo().device());
        assertCapturedSkin(data, packet.getEntries().getFirst().getSkin());
    }

    @ParameterizedTest(name = "{0}, source={1}, source OS={2}, recipient={3}, self first={4}")
    @MethodSource("replayCases")
    void capturedAppearanceSequenceEncodesAndDecodesWithoutTrailingBytes(
            Protocol protocol, String sourceCapture, int device, String recipientCapture, boolean selfFirst
    ) throws Exception {
        var sourceData = capture(sourceCapture);
        sourceData.addProperty("DeviceOS", device);
        var source = player(decodeAppearance(sourceData), SOURCE_ID, "SourceFixture", 3);
        var recipient = player(decodeAppearance(capture(recipientCapture)), RECIPIENT_ID, "RecipientFixture", 4);
        var packets = appearanceSequence(protocol, source, recipient, selfFirst);
        assertEquals(List.of(AddPlayerPacket.class, SetEntityDataPacket.class, MobArmorEquipmentPacket.class,
                        MobEquipmentPacket.class, MobEquipmentPacket.class, PlayerSkinPacket.class,
                        PlayerListPacket.class, UpdateAbilitiesPacket.class),
                packets.stream().map(Object::getClass).toList());

        // Separate helpers avoid sharing encoder state with the simulated receiver.
        var sender = helper(protocol);
        var receiver = helper(protocol);
        for (var packet : packets) {
            var decoded = roundTrip(protocol, sender, receiver, packet);
            assertEquals(packet.getClass(), decoded.getClass());
            if (decoded instanceof PlayerSkinPacket skin) {
                assertEquals(SOURCE_ID, skin.getUuid());
                assertCapturedSkin(sourceData, skin.getSkin());
            }
            if (decoded instanceof PlayerListPacket list) {
                assertEquals(2, list.getEntries().size());
                var sourceEntry = list.getEntries().stream()
                        .filter(entry -> SOURCE_ID.equals(entry.getUuid())).findFirst().orElseThrow();
                var windowsPlatform = protocol.getProtocolVersion() >= 2168 ? BuildPlatform.WIN32 : BuildPlatform.UWP;
                assertEquals(device == 7 ? windowsPlatform : BuildPlatform.GOOGLE, sourceEntry.getBuildPlatform());
                assertEquals("", sourceEntry.getPlatformChatId());
                assertCapturedSkin(sourceData, sourceEntry.getSkin());
                var selfEntry = list.getEntries().stream()
                        .filter(entry -> RECIPIENT_ID.equals(entry.getUuid())).findFirst().orElseThrow();
                assertCapturedSkin(capture(recipientCapture), selfEntry.getSkin());
            }
        }
    }

    @Test
    void capturedLegacyPersonaRetainsItsTextureMeshAndAnimatedFace() throws Exception {
        var data = capture(PERSONA_CAPTURE);
        assertEquals("1.21.100", data.get("GameVersion").getAsString());
        assertEquals("ebbe9ff78e8a65eb5f44deb84d5a613a7a355412567ccf9e31287b9aab8cd593",
                sha256(Base64.getDecoder().decode(data.get("SkinData").getAsString())));
        assertEquals("78b2a500de6a7aec9d2e95c7d5d0eca9d277f1dafd25c0738bf0e3f0c92bb61c",
                sha256(Base64.getDecoder().decode(data.get("SkinGeometryData").getAsString())));
        var skin = decodeAppearance(data).getSkin();
        assertTrue(skin.isValid());
        assertTrue(skin.personaSkin());
        assertEquals(256, skin.skinData().width());
        assertEquals(256, skin.skinData().height());
        assertEquals(1, skin.animations().size());
        assertEquals(2, JsonParser.parseString(skin.skinGeometry()).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").size());
        assertEquals("0011223344556677", skin.playFabId());
    }

    @Test
    void bothClientPlayFabKeysAreAcceptedWithoutInventingMissingIds() throws Exception {
        var data = capture(PERSONA_CAPTURE);
        data.remove("PlayFabId");
        data.remove("PlayFabID");
        assertNull(decodeAppearance(data).getSkin().playFabId());
        data.addProperty("PlayFabID", "legacy-id");
        assertEquals("legacy-id", decodeAppearance(data).getSkin().playFabId());
        data.addProperty("PlayFabId", "current-id");
        assertEquals("current-id", decodeAppearance(data).getSkin().playFabId());
        data.add("PlayFabId", com.google.gson.JsonNull.INSTANCE);
        assertEquals("legacy-id", decodeAppearance(data).getSkin().playFabId());
    }

    @Test
    void changingOnlyTheSourcePlatformDoesNotChangeTheSerializedSkin() throws Exception {
        var protocol = protocol();
        var windowsData = capture(SOURCE_CAPTURE);
        var androidData = windowsData.deepCopy();
        androidData.addProperty("DeviceOS", 1);
        var windows = player(decodeAppearance(windowsData), SOURCE_ID, "SourceFixture", 3);
        var android = player(decodeAppearance(androidData), SOURCE_ID, "SourceFixture", 3);
        var windowsSkin = protocol.getEncoder().encodePlayerSkin(windows.entity(), true).iterator().next();
        var androidSkin = protocol.getEncoder().encodePlayerSkin(android.entity(), true).iterator().next();
        assertArrayEquals(encode(protocol, windowsSkin), encode(protocol, androidSkin));

        var windowsList = protocol.getEncoder().encodePlayerList(List.of(windows.player()), true, true);
        var androidList = protocol.getEncoder().encodePlayerList(List.of(android.player()), true, true);
        assertEquals(BuildPlatform.WIN32, windowsList.getEntries().getFirst().getBuildPlatform());
        assertEquals(BuildPlatform.GOOGLE, androidList.getEntries().getFirst().getBuildPlatform());
        windowsList.getEntries().getFirst().setBuildPlatform(BuildPlatform.UNKNOWN);
        androidList.getEntries().getFirst().setBuildPlatform(BuildPlatform.UNKNOWN);
        assertArrayEquals(encode(protocol, windowsList), encode(protocol, androidList));
    }

    @ParameterizedTest(name = "player list profile hash: {0}")
    @MethodSource("supportedProtocols")
    void capturedPersonaProfileHashMustSurviveThePlayerList(Protocol protocol) throws Exception {
        var data = capture("android-2193-persona");
        var recipient = player(decodeAppearance(data), RECIPIENT_ID, "RecipientFixture", 4);
        var packet = protocol.getEncoder().encodePlayerList(List.of(recipient.player()), true, true);
        String capturedHash = data.get("ProfileHash").getAsString();
        assertFalse(capturedHash.isEmpty());
        assertEquals(capturedHash, recipient.player().getLoginData().getSkin().profileHash());
        assertEquals(capturedHash, packet.getEntries().getFirst().getSkin().getProfileHash());
        var decoded = (PlayerListPacket) roundTrip(protocol, helper(protocol), helper(protocol), packet);
        assertEquals(wireProfileHash(protocol, capturedHash),
                Objects.requireNonNullElse(decoded.getEntries().getFirst().getSkin().getProfileHash(), ""),
                "New codecs must retain the hash; legacy codecs must omit the unsupported field");
    }

    @ParameterizedTest(name = "player skin profile hash: {0}")
    @MethodSource("supportedProtocols")
    void capturedPersonaProfileHashMustSurviveSkinUpdates(Protocol protocol) throws Exception {
        var data = capture("android-2193-persona");
        String capturedHash = data.get("ProfileHash").getAsString();
        var source = player(decodeAppearance(data), SOURCE_ID, "SourceFixture", 3);
        var packet = (PlayerSkinPacket) protocol.getEncoder().encodePlayerSkin(source.entity(), true).iterator().next();
        assertEquals(capturedHash, packet.getSkin().getProfileHash());
        var decoded = (PlayerSkinPacket) roundTrip(protocol, helper(protocol), helper(protocol), packet);
        assertEquals(wireProfileHash(protocol, capturedHash),
                SkinConvertor.fromSerializedSkin(decoded.getSkin()).profileHash());
    }

    static Stream<Arguments> optionalProfileHashes() {
        return supportedProtocols().flatMap(protocol -> Stream.of("absent", "null", "empty")
                .map(state -> Arguments.of(protocol, state)));
    }

    @ParameterizedTest(name = "optional hash {1}: {0}")
    @MethodSource("optionalProfileHashes")
    void missingProfileHashesRemainUsableOnEveryProtocol(Protocol protocol, String state) throws Exception {
        var data = capture(SOURCE_CAPTURE);
        if (state.equals("null")) {
            data.add("ProfileHash", com.google.gson.JsonNull.INSTANCE);
        } else if (state.equals("empty")) {
            data.addProperty("ProfileHash", "");
        } else {
            data.remove("ProfileHash");
        }
        var login = decodeAppearance(data);
        assertEquals("", login.getSkin().profileHash());
        var source = player(login, SOURCE_ID, "SourceFixture", 3);
        var list = protocol.getEncoder().encodePlayerList(List.of(source.player()), true, true);
        var decodedList = (PlayerListPacket) roundTrip(protocol, helper(protocol), helper(protocol), list);
        assertEquals("", SkinConvertor.fromSerializedSkin(decodedList.getEntries().getFirst().getSkin()).profileHash());
        var skin = protocol.getEncoder().encodePlayerSkin(source.entity(), true).iterator().next();
        var decodedSkin = (PlayerSkinPacket) roundTrip(protocol, helper(protocol), helper(protocol), skin);
        assertEquals("", SkinConvertor.fromSerializedSkin(decodedSkin.getSkin()).profileHash());
    }

    private static String wireProfileHash(Protocol protocol, String hash) {
        // The profile hash is part of the skin wire layout starting at Bedrock v2168.
        return protocol.getProtocolVersion() >= 2168 ? hash : "";
    }

    @Test
    void capturedSixDigitPersonaSkinColorMustRemainOpaque() throws Exception {
        var protocol = protocol();
        var data = capture("android-2193-persona");
        var recipient = player(decodeAppearance(data), RECIPIENT_ID, "RecipientFixture", 4);
        var packet = protocol.getEncoder().encodePlayerList(List.of(recipient.player()), true, true);
        var decoded = (PlayerListPacket) roundTrip(protocol, helper(protocol), helper(protocol), packet);
        int expected = (int) Long.parseLong(data.get("SkinColor").getAsString().substring(1), 16) | 0xff000000;
        assertEquals(expected, decoded.getEntries().getFirst().getSkin().getColor().getRGB(),
                "A six-digit RGB skin colour has an implicit opaque alpha, as in BDS");
    }

    private static List<BedrockPacket> appearanceSequence(
            Protocol protocol, FixturePlayer source, FixturePlayer recipient, boolean selfFirst
    ) {
        var encoder = protocol.getEncoder();
        var packets = new ArrayList<BedrockPacket>();
        packets.add(encoder.encodeEntitySpawn(source.entity()));
        packets.add(encoder.encodeEntityState(source.entity()));
        packets.add(encoder.encodeEntityArmor(source.entity()));
        packets.add(encoder.encodeEntityHand(source.entity()));
        packets.add(encoder.encodeEntityOffhand(source.entity()));
        packets.addAll(encoder.encodePlayerSkin(source.entity(), true));
        packets.add(encoder.encodePlayerList(selfFirst
                ? List.of(recipient.player(), source.player()) : List.of(source.player(), recipient.player()), true, true));
        packets.add(encoder.encodePlayerAbilities(SOURCE_ID.getLeastSignificantBits(), CommandPermission.GAME_DIRECTORS,
                PlayerPermission.OPERATOR, List.of(Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES,
                        Ability.OPEN_CONTAINERS, Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS,
                        Ability.OPERATOR_COMMANDS, Ability.MAY_FLY, Ability.INSTABUILD), 0.1f, 0.05f, 1.0f));
        return packets;
    }

    private static FixturePlayer player(AllayLoginData appearance, UUID id, String name, long runtimeId) {
        var login = spy(appearance);
        doReturn(id).when(login).getUuid();
        doReturn("0").when(login).getXuid();
        var entity = mock(EntityPlayer.class, RETURNS_DEEP_STUBS);
        when(entity.getUniqueId()).thenReturn(id);
        when(entity.getRuntimeId()).thenReturn(runtimeId);
        when(entity.getSkin()).thenReturn(appearance.getSkin());
        when(entity.isActualPlayer()).thenReturn(true);
        when(entity.getNameTag()).thenReturn(name);
        when(entity.hasNameTag()).thenReturn(true);
        when(entity.isNameTagAlwaysShow()).thenReturn(true);
        when(entity.hasEntityCollision()).thenReturn(true);
        when(entity.hasGravity()).thenReturn(true);
        when(entity.canBreathe()).thenReturn(true);
        when(entity.getAirSupplyTicks()).thenReturn(300);
        when(entity.getAirSupplyMaxTicks()).thenReturn(300);
        when(entity.getEffects()).thenReturn(Map.of());
        when(entity.getPropertyValues()).thenReturn(Map.of());
        when(entity.getLocation()).thenReturn(new Location3d(
                -0.7553028, 83.00001, 4.292568, 1.3987885, 78.960724, mock(Dimension.class)));
        when(entity.getAABB()).thenReturn(new AABBd(0, 0, 0, 0.6, 1.8, 0.6));
        when(entity.getScale()).thenReturn(1.0);
        when(entity.getGameMode()).thenReturn(GameMode.CREATIVE);
        doReturn(EntityTypes.PLAYER).when(entity).getEntityType();
        // Generic container return types need explicit mocks; deep stubs only know the erased Container type.
        var inventory = mock(InventoryContainer.class);
        var offhand = mock(OffhandContainer.class);
        var armor = mock(ArmorContainer.class);
        doReturn(null).when(entity).getContainer(ContainerTypes.ENTITY_HAND);
        doReturn(inventory).when(entity).getContainer(ContainerTypes.INVENTORY);
        doReturn(offhand).when(entity).getContainer(ContainerTypes.OFFHAND);
        doReturn(armor).when(entity).getContainer(ContainerTypes.ARMOR);
        when(inventory.getItemInHand()).thenReturn(ItemAirStack.AIR_STACK);
        when(offhand.getOffhand()).thenReturn(ItemAirStack.AIR_STACK);
        when(armor.getHelmet()).thenReturn(ItemAirStack.AIR_STACK);
        when(armor.getChestplate()).thenReturn(ItemAirStack.AIR_STACK);
        when(armor.getLeggings()).thenReturn(ItemAirStack.AIR_STACK);
        when(armor.getBoots()).thenReturn(ItemAirStack.AIR_STACK);
        var player = mock(Player.class);
        when(player.getLoginData()).thenReturn(login);
        when(player.getControlledEntity()).thenReturn(entity);
        when(player.getOriginName()).thenReturn(name);
        return new FixturePlayer(player, entity);
    }

    private static JsonObject capture(String name) throws Exception {
        try (var stream = CapturedPlayerPacketReplayTest.class.getResourceAsStream("/player-packet-replay/" + name + ".json.gz")) {
            assertNotNull(stream, name);
            try (var reader = new InputStreamReader(new GZIPInputStream(stream), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static AllayLoginData decodeAppearance(JsonObject data) throws Exception {
        // Exercise the production appearance parser without saving or replaying the user's signed authentication tokens.
        var login = new AllayLoginData();
        var method = AllayLoginData.class.getDeclaredMethod("decodeSkinData", String.class, boolean.class);
        method.setAccessible(true);
        String token = "fixture." + Base64.getEncoder().encodeToString(data.toString().getBytes(StandardCharsets.UTF_8)) + ".fixture";
        method.invoke(login, token, false);
        return login;
    }

    private static void assertCapturedSkin(JsonObject capture, SerializedSkin actual) {
        assertEquals(capture.get("SkinId").getAsString(), actual.getSkinId());
        assertEquals(capture.get("SkinImageWidth").getAsInt(), actual.getSkinData().getWidth());
        assertEquals(capture.get("SkinImageHeight").getAsInt(), actual.getSkinData().getHeight());
        assertArrayEquals(Base64.getDecoder().decode(capture.get("SkinData").getAsString()), actual.getSkinData().getImage());
        String geometry = new String(Base64.getDecoder().decode(capture.get("SkinGeometryData").getAsString()),
                StandardCharsets.UTF_8).trim();
        assertEquals(geometry.startsWith("{") ? geometry : "{}", actual.getGeometryData());
        assertEquals(capture.get("PersonaSkin").getAsBoolean(), actual.isPersona());
        assertEquals(capture.get("PremiumSkin").getAsBoolean(), actual.isPremium());
        assertEquals(capture.getAsJsonArray("AnimatedImageData").size(), actual.getAnimations().size());
        assertEquals(capture.getAsJsonArray("PersonaPieces").size(), actual.getPersonaPieces().size());
        String playFabId = "";
        for (var key : List.of("PlayFabId", "PlayFabID")) {
            if (capture.has(key) && capture.get(key).isJsonPrimitive()) {
                playFabId = capture.get(key).getAsString();
                break;
            }
        }
        assertEquals(playFabId, actual.getPlayFabId());
        for (int i = 0; i < actual.getAnimations().size(); i++) {
            assertArrayEquals(Base64.getDecoder().decode(capture.getAsJsonArray("AnimatedImageData")
                    .get(i).getAsJsonObject().get("Image").getAsString()),
                    actual.getAnimations().get(i).image().getImage());
        }
        String color = capture.get("SkinColor").getAsString().replace("#", "");
        int expectedArgb = (int) Long.parseLong(color, 16);
        if (color.length() == 6) expectedArgb |= 0xff000000;
        assertEquals(expectedArgb, actual.getColor().getRGB());
    }

    private static Protocol protocol() {
        var protocol = ProtocolRegistry.getDefault().resolve(ClientVariant.INTERNATIONAL, 2193);
        assertNotNull(protocol);
        return protocol;
    }

    private static BedrockCodecHelper helper(Protocol protocol) {
        var helper = protocol.getCodec().createHelper();
        helper.setItemDefinitions(SimpleDefinitionRegistry.<ItemDefinition>builder()
                .addAll(protocol.getData().itemDefinitions()).build());
        helper.setBlockDefinitions(SimpleDefinitionRegistry.<BlockDefinition>builder()
                .addAll(protocol.getData().blockDefinitions()).build());
        return helper;
    }

    private static BedrockPacket roundTrip(Protocol protocol, BedrockCodecHelper sender,
                                           BedrockCodecHelper receiver, BedrockPacket packet) {
        var buffer = Unpooled.buffer();
        try {
            protocol.getCodec().tryEncode(sender, buffer, packet);
            assertTrue(buffer.isReadable(), packet.getClass().getSimpleName());
            int id = protocol.getCodec().getPacketDefinition(packet.getClass()).id();
            var decoded = protocol.getCodec().tryDecode(receiver, buffer, id);
            assertFalse(buffer.isReadable(), () -> packet.getClass().getSimpleName() + " leaves trailing bytes");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static byte[] encode(Protocol protocol, BedrockPacket packet) {
        var buffer = Unpooled.buffer();
        try {
            protocol.getCodec().tryEncode(helper(protocol), buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record FixturePlayer(Player player, EntityPlayer entity) {
    }
}
