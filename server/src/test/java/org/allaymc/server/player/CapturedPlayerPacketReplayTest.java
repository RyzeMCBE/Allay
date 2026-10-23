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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Replays captured appearance data through the real login decoder, packet encoder and v2193 codec.
 * This is not a Minecraft client, a RakNet replay, or an authentication test.
 */
@ExtendWith(AllayTestExtension.class)
class CapturedPlayerPacketReplayTest {
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-8000-000000000003");
    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-8000-000000000004");
    private static final String SOURCE_CAPTURE = "windows-844-party";

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
        return Stream.of(7, 1).flatMap(device ->
                Stream.of("android-2193-classic", "android-2193-persona").flatMap(recipient ->
                        Stream.of(false, true).map(selfFirst -> Arguments.of(device, recipient, selfFirst))));
    }

    @ParameterizedTest(name = "source OS={0}, recipient={1}, self first={2}")
    @MethodSource("replayCases")
    void capturedAppearanceSequenceEncodesAndDecodesWithoutTrailingBytes(
            int device, String recipientCapture, boolean selfFirst
    ) throws Exception {
        var protocol = protocol();
        var sourceData = capture(SOURCE_CAPTURE);
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
                assertEquals(device == 7 ? BuildPlatform.UWP : BuildPlatform.GOOGLE, sourceEntry.getBuildPlatform());
                assertEquals("", sourceEntry.getPlatformChatId());
                assertCapturedSkin(sourceData, sourceEntry.getSkin());
                var selfEntry = list.getEntries().stream()
                        .filter(entry -> RECIPIENT_ID.equals(entry.getUuid())).findFirst().orElseThrow();
                assertCapturedSkin(capture(recipientCapture), selfEntry.getSkin());
            }
        }
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
        assertEquals(BuildPlatform.UWP, windowsList.getEntries().getFirst().getBuildPlatform());
        assertEquals(BuildPlatform.GOOGLE, androidList.getEntries().getFirst().getBuildPlatform());
        windowsList.getEntries().getFirst().setBuildPlatform(BuildPlatform.UNKNOWN);
        androidList.getEntries().getFirst().setBuildPlatform(BuildPlatform.UNKNOWN);
        assertArrayEquals(encode(protocol, windowsList), encode(protocol, androidList));
    }

    @Test
    void capturedPersonaProfileHashMustSurviveThePlayerList() throws Exception {
        var protocol = protocol();
        var data = capture("android-2193-persona");
        var recipient = player(decodeAppearance(data), RECIPIENT_ID, "RecipientFixture", 4);
        var packet = protocol.getEncoder().encodePlayerList(List.of(recipient.player()), true, true);
        var decoded = (PlayerListPacket) roundTrip(protocol, helper(protocol), helper(protocol), packet);
        assertFalse(data.get("ProfileHash").getAsString().isEmpty());
        assertEquals(data.get("ProfileHash").getAsString(), decoded.getEntries().getFirst().getSkin().getProfileHash(),
                "The captured appearance cache key must survive login decoding and player-list serialization");
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
