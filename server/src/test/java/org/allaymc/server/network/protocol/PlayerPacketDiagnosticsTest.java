package org.allaymc.server.network.protocol;

import org.allaymc.server.ServerSettings;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPacketDiagnosticsTest {
    private static final UUID SOURCE = id(1);
    private static final UUID RECIPIENT = id(2);
    private static final UUID SECOND_SOURCE = id(3);
    private static final UUID OTHER = id(4);
    private static final UUID NPC = id(5);
    private final PlayerPacketDiagnostics.SourceRegistry sources = new PlayerPacketDiagnostics.SourceRegistry();

    @BeforeEach
    void registerPlayers() {
        sources.register(SOURCE, 844, SOURCE);
        sources.register(SECOND_SOURCE, 844, SECOND_SOURCE);
        sources.register(RECIPIENT, 2193, RECIPIENT);
        sources.register(OTHER, 1001, OTHER);
    }

    @Test
    void diagnosticsAreDisabledByDefaultAndInvalidSettingsFailOpen() {
        assertFalse(PlayerPacketDiagnostics.create(new ServerSettings.PlayerPacketDiagnosticsSettings()).isEnabled());
        assertFalse(diagnostic(settings("UNKNOWN_PACKET")).isEnabled());
        assertFalse(diagnostic(settings("LEVEL_CHUNK")).isEnabled());
        assertFalse(diagnostic(settings("*").sourceProtocol(0)).isEnabled());
        assertFalse(diagnostic(settings("*").recipientProtocol(0)).isEnabled());
        assertFalse(diagnostic(settings("*").maxLogPackets(0)).isEnabled());
        var packet = skin(SOURCE);
        assertSame(packet, PlayerPacketDiagnostics.disabled().filter(RECIPIENT, 2193, packet));
    }

    @Test
    void traceOnlyPreservesEveryPacket() {
        var diagnostic = diagnostic(settings());
        for (var packet : List.of(add(SOURCE, 17), skin(SOURCE), list(SOURCE))) {
            assertSame(packet, diagnostic.filter(RECIPIENT, 2193, packet));
        }
    }

    @Test
    void protocolSelectionCoversAllSourcePlayersAndAllMatchingRecipients() {
        var diagnostic = diagnostic(settings("PLAYER_SKIN"));
        assertNull(diagnostic.filter(RECIPIENT, 2193, skin(SOURCE)));
        assertNull(diagnostic.filter(RECIPIENT, 2193, skin(SECOND_SOURCE)));
        assertNull(diagnostic.filter(id(9), 2193, skin(SECOND_SOURCE)));
        var packet = skin(SOURCE);
        assertSame(packet, diagnostic.filter(RECIPIENT, 844, packet));
        var unrelated = skin(OTHER);
        assertSame(unrelated, diagnostic.filter(RECIPIENT, 2193, unrelated));
    }

    @Test
    void excludingSkinUpdatesKeepsTheRequiredListAndUnknownNpcSkins() {
        var diagnostic = diagnostic(settings("PLAYER_SKIN"));
        var list = list(SOURCE, SECOND_SOURCE);
        assertSame(list, diagnostic.filter(RECIPIENT, 2193, list));
        assertNull(diagnostic.filter(RECIPIENT, 2193, skin(SOURCE)));
        var npc = skin(NPC);
        assertSame(npc, diagnostic.filter(RECIPIENT, 2193, npc));
    }

    @Test
    void mixedPlayerListsKeepSelfOtherProtocolsAndNpcsWithoutMutatingSharedEntries() {
        var diagnostic = diagnostic(settings("PLAYER_LIST"));
        var original = list(SOURCE, SECOND_SOURCE, RECIPIENT, OTHER, NPC);
        var originalEntries = List.copyOf(original.getEntries());
        var result = assertInstanceOf(PlayerListPacket.class, diagnostic.filter(RECIPIENT, 2193, original));
        assertNotSame(original, result);
        assertEquals(original.getAction(), result.getAction());
        assertEquals(originalEntries.subList(2, 5), result.getEntries());
        assertEquals(originalEntries, original.getEntries());
        assertSame(original, diagnostic.filter(RECIPIENT, 818, original));
    }

    @Test
    void sourceOnlyPlayerListsAreOmittedIncludingRemoveEntries() {
        var diagnostic = diagnostic(settings("PLAYER_LIST"));
        assertNull(diagnostic.filter(RECIPIENT, 2193, list(SOURCE, SECOND_SOURCE)));
        var removal = list(SOURCE, SECOND_SOURCE);
        removal.setAction(PlayerListPacket.Action.REMOVE);
        removal.getEntries().forEach(entry -> entry.setAction(PlayerListPacket.Action.REMOVE));
        assertNull(diagnostic.filter(RECIPIENT, 2193, removal));
        assertEquals(2, removal.getEntries().size());
    }

    @Test
    void wildcardFiltersInitialSourcePacketsAndLeavesTheWorldAndNpcsAlone() {
        var diagnostic = diagnostic(settings("*"));
        assertNull(diagnostic.filter(RECIPIENT, 2193, add(SOURCE, 17)));
        var armor = new MobArmorEquipmentPacket();
        armor.setRuntimeEntityId(17);
        var equipment = new MobEquipmentPacket();
        equipment.setRuntimeEntityId(17);
        var abilities = new UpdateAbilitiesPacket();
        abilities.setUniqueEntityId(SOURCE.getLeastSignificantBits());
        for (var packet : List.of(metadata(17), armor, equipment, abilities, skin(SOURCE), list(SOURCE))) {
            assertNull(diagnostic.filter(RECIPIENT, 2193, packet));
        }
        var npc = add(NPC, 18);
        assertSame(npc, diagnostic.filter(RECIPIENT, 2193, npc));
        var npcData = metadata(18);
        assertSame(npcData, diagnostic.filter(RECIPIENT, 2193, npcData));
        var world = new LevelChunkPacket();
        assertSame(world, diagnostic.filter(RECIPIENT, 2193, world));
    }

    @Test
    void multipleRuntimeIdsAreLearnedEvenWhenAddPlayerIsOmitted() {
        var diagnostic = diagnostic(settings("ADD_PLAYER", "SET_ENTITY_DATA"));
        assertNull(diagnostic.filter(RECIPIENT, 2193, add(SOURCE, 17)));
        assertNull(diagnostic.filter(RECIPIENT, 2193, add(SECOND_SOURCE, 18)));
        assertNull(diagnostic.filter(RECIPIENT, 2193, metadata(17)));
        assertNull(diagnostic.filter(RECIPIENT, 2193, metadata(18)));
        assertNull(diagnostic.filter(RECIPIENT, 2193, add(SOURCE, 20)));
        var old = metadata(17);
        assertSame(old, diagnostic.filter(RECIPIENT, 2193, old));
        assertNull(diagnostic.filter(RECIPIENT, 2193, metadata(20)));
        assertNull(diagnostic.filter(RECIPIENT, 2193, metadata(18)));
    }

    @Test
    void runtimeIdsAreNeverGuessedOrSharedBetweenConnections() {
        var first = diagnostic(settings("SET_ENTITY_DATA"));
        var second = diagnostic(settings("SET_ENTITY_DATA"));
        var packet = metadata(17);
        assertSame(packet, first.filter(RECIPIENT, 2193, packet));
        first.filter(RECIPIENT, 2193, add(SOURCE, 17));
        assertNull(first.filter(RECIPIENT, 2193, packet));
        assertSame(packet, second.filter(RECIPIENT, 2193, packet));
    }

    @Test
    void newlyRegisteredSourceIsRecognizedBeforeItAppearsInTheWorld() {
        var diagnostic = diagnostic(settings("PLAYER_SKIN", "UPDATE_ABILITIES"));
        var joining = id(10);
        var packet = skin(joining);
        assertSame(packet, diagnostic.filter(RECIPIENT, 2193, packet));
        sources.register(joining, 844, joining);
        assertNull(diagnostic.filter(RECIPIENT, 2193, packet));
        var abilities = new UpdateAbilitiesPacket();
        abilities.setUniqueEntityId(joining.getLeastSignificantBits());
        assertNull(diagnostic.filter(RECIPIENT, 2193, abilities));
    }

    @Test
    void selfPacketsArePreservedEvenWhenBothSelectedProtocolsAreTheSame() {
        var diagnostic = diagnostic(settings("*").sourceProtocol(2193));
        var self = skin(RECIPIENT);
        assertSame(self, diagnostic.filter(RECIPIENT, 2193, self));
        var sameProtocolPeer = id(11);
        sources.register(sameProtocolPeer, 2193, sameProtocolPeer);
        var original = list(RECIPIENT, sameProtocolPeer);
        var filtered = assertInstanceOf(PlayerListPacket.class, diagnostic.filter(RECIPIENT, 2193, original));
        assertEquals(List.of(original.getEntries().getFirst()), filtered.getEntries());
    }

    @Test
    void aProtocolChangeOnReconnectInvalidatesOldTrackedEntityIds() {
        var diagnostic = diagnostic(settings("*"));
        diagnostic.filter(RECIPIENT, 2193, add(SOURCE, 17));
        sources.register(SOURCE, 1001, new Object());
        var packet = metadata(17);
        assertSame(packet, diagnostic.filter(RECIPIENT, 2193, packet));
        var skin = skin(SOURCE);
        assertSame(skin, diagnostic.filter(RECIPIENT, 2193, skin));
    }

    @Test
    void disconnectCleanupPreservesANewerConnectionWithTheSameUuid() {
        var oldConnection = new Object();
        var newConnection = new Object();
        sources.register(SOURCE, 844, oldConnection);
        sources.register(SOURCE, 1001, newConnection);
        sources.unregister(SOURCE, oldConnection);
        assertEquals(1001, sources.protocol(SOURCE));
        sources.unregister(SOURCE, newConnection);
        assertEquals(0, sources.protocol(SOURCE));
    }

    @Test
    void removalsAfterDisconnectRemainMatchedInEitherPacketOrderThenReleaseRuntimeIds() {
        for (boolean entityFirst : List.of(true, false)) {
            var owner = new Object();
            sources.register(SOURCE, 844, owner);
            var diagnostic = diagnostic(settings("*"));
            diagnostic.filter(RECIPIENT, 2193, add(SOURCE, 17));
            diagnostic.filter(RECIPIENT, 2193, list(SOURCE));
            sources.unregister(SOURCE, owner);
            var remove = new RemoveEntityPacket();
            remove.setUniqueEntityId(SOURCE.getLeastSignificantBits());
            var removalList = list(SOURCE);
            removalList.setAction(PlayerListPacket.Action.REMOVE);
            removalList.getEntries().getFirst().setAction(PlayerListPacket.Action.REMOVE);
            if (entityFirst) {
                assertNull(diagnostic.filter(RECIPIENT, 2193, remove));
                assertNull(diagnostic.filter(RECIPIENT, 2193, removalList));
            } else {
                assertNull(diagnostic.filter(RECIPIENT, 2193, removalList));
                assertNull(diagnostic.filter(RECIPIENT, 2193, remove));
            }
            var laterNpcData = metadata(17);
            assertSame(laterNpcData, diagnostic.filter(RECIPIENT, 2193, laterNpcData));
        }
    }

    @Test
    void logLimitsDoNotDisableFilteringAndSettingsAreSnapshotted() {
        var settings = settings("PLAYER_SKIN").maxLogPackets(1);
        var diagnostic = diagnostic(settings);
        settings.excludedPackets(List.of()).sourceProtocol(1001);
        for (int i = 0; i < 5; i++) assertNull(diagnostic.filter(RECIPIENT, 2193, skin(SOURCE)));
    }

    private PlayerPacketDiagnostics diagnostic(ServerSettings.PlayerPacketDiagnosticsSettings settings) {
        return PlayerPacketDiagnostics.create(settings, sources);
    }

    private static ServerSettings.PlayerPacketDiagnosticsSettings settings(String... excluded) {
        return new ServerSettings.PlayerPacketDiagnosticsSettings().enabled(true)
                .sourceProtocol(844).recipientProtocol(2193).excludedPackets(List.of(excluded));
    }

    private static UUID id(int value) {
        return new UUID(0, value);
    }

    private static AddPlayerPacket add(UUID source, long runtimeId) {
        var packet = new AddPlayerPacket();
        packet.setUuid(source);
        packet.setRuntimeEntityId(runtimeId);
        packet.setUniqueEntityId(source.getLeastSignificantBits());
        return packet;
    }

    private static SetEntityDataPacket metadata(long runtimeId) {
        var packet = new SetEntityDataPacket();
        packet.setRuntimeEntityId(runtimeId);
        return packet;
    }

    private static PlayerSkinPacket skin(UUID source) {
        var packet = new PlayerSkinPacket();
        packet.setUuid(source);
        return packet;
    }

    private static PlayerListPacket list(UUID... players) {
        var packet = new PlayerListPacket();
        packet.setAction(PlayerListPacket.Action.ADD);
        for (var uuid : players) {
            var entry = new PlayerListPacket.Entry(uuid);
            entry.setAction(PlayerListPacket.Action.ADD);
            entry.setEntityId(uuid.getLeastSignificantBits());
            packet.getEntries().add(entry);
        }
        return packet;
    }
}
