package org.allaymc.server.network.protocol;

import org.allaymc.server.ServerSettings;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPacketDiagnosticsTest {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void diagnosticsAreDisabledByDefaultAndInvalidSettingsFailOpen() {
        assertFalse(PlayerPacketDiagnostics.create(new ServerSettings.PlayerPacketDiagnosticsSettings()).isEnabled());
        assertFalse(PlayerPacketDiagnostics.create(settings("UNKNOWN_PACKET")).isEnabled());
        assertFalse(PlayerPacketDiagnostics.create(settings("LEVEL_CHUNK")).isEnabled());
        assertFalse(PlayerPacketDiagnostics.create(settings("*").sourceUuid("invalid")).isEnabled());
        assertFalse(PlayerPacketDiagnostics.create(settings("*").recipientUuid(SOURCE.toString())).isEnabled());
        assertFalse(PlayerPacketDiagnostics.create(settings("*").maxLogPackets(0)).isEnabled());
        var packet = sourceSkin();
        assertSame(packet, PlayerPacketDiagnostics.disabled().filter(RECIPIENT, 2193, packet));
    }

    @Test
    void traceOnlyPreservesEveryPacket() {
        var diagnostic = PlayerPacketDiagnostics.create(settings());
        for (var packet : List.of(sourceAdd(), sourceSkin(), sourceList())) {
            assertSame(packet, diagnostic.filter(RECIPIENT, 2193, packet));
        }
    }

    @Test
    void filterOnlyAppliesToTheConfiguredRecipientAndProtocol() {
        var diagnostic = PlayerPacketDiagnostics.create(settings("PLAYER_SKIN"));
        var packet = sourceSkin();
        assertSame(packet, diagnostic.filter(OTHER, 2193, packet));
        assertSame(packet, diagnostic.filter(RECIPIENT, 844, packet));
        assertNull(diagnostic.filter(RECIPIENT, 2193, packet));
    }

    @Test
    void excludingSkinUpdatesKeepsTheRequiredPlayerListAndOtherSkins() {
        var diagnostic = PlayerPacketDiagnostics.create(settings("PLAYER_SKIN"));
        var list = sourceList();
        assertSame(list, diagnostic.filter(RECIPIENT, 2193, list));
        assertNull(diagnostic.filter(RECIPIENT, 2193, sourceSkin()));
        var otherSkin = sourceSkin();
        otherSkin.setUuid(OTHER);
        assertSame(otherSkin, diagnostic.filter(RECIPIENT, 2193, otherSkin));
    }

    @Test
    void filteringABatchedPlayerListDoesNotMutateSharedEntriesOrOtherRecipients() {
        var diagnostic = PlayerPacketDiagnostics.create(settings("PLAYER_LIST"));
        var original = sourceList();
        var sourceEntry = original.getEntries().getFirst();
        var otherEntry = new PlayerListPacket.Entry(OTHER);
        otherEntry.setAction(PlayerListPacket.Action.ADD);
        original.getEntries().add(otherEntry);
        var result = assertInstanceOf(PlayerListPacket.class, diagnostic.filter(RECIPIENT, 2193, original));
        assertNotSame(original, result);
        assertEquals(original.getAction(), result.getAction());
        assertEquals(List.of(otherEntry), result.getEntries());
        assertEquals(List.of(sourceEntry, otherEntry), original.getEntries());
        assertSame(original, diagnostic.filter(OTHER, 2193, original));
    }

    @Test
    void sourceOnlyPlayerListIsOmittedIncludingRemoveEntries() {
        var diagnostic = PlayerPacketDiagnostics.create(settings("PLAYER_LIST"));
        var list = sourceList();
        assertNull(diagnostic.filter(RECIPIENT, 2193, list));
        list.setAction(PlayerListPacket.Action.REMOVE);
        list.getEntries().getFirst().setAction(PlayerListPacket.Action.REMOVE);
        assertNull(diagnostic.filter(RECIPIENT, 2193, list));
        assertEquals(1, list.getEntries().size());
    }

    @Test
    void wildcardFiltersTheInitialSourcePacketsAndLeavesTheWorldAndNpcsAlone() {
        var diagnostic = PlayerPacketDiagnostics.create(settings("*"));
        assertNull(diagnostic.filter(RECIPIENT, 2193, sourceAdd()));
        var metadata = new SetEntityDataPacket();
        metadata.setRuntimeEntityId(17);
        var armor = new MobArmorEquipmentPacket();
        armor.setRuntimeEntityId(17);
        var equipment = new MobEquipmentPacket();
        equipment.setRuntimeEntityId(17);
        var abilities = new UpdateAbilitiesPacket();
        abilities.setUniqueEntityId(SOURCE.getLeastSignificantBits());
        for (var packet : List.of(metadata, armor, equipment, abilities, sourceSkin(), sourceList())) {
            assertNull(diagnostic.filter(RECIPIENT, 2193, packet));
        }
        var npc = sourceAdd();
        npc.setUuid(OTHER);
        npc.setRuntimeEntityId(18);
        assertSame(npc, diagnostic.filter(RECIPIENT, 2193, npc));
        metadata.setRuntimeEntityId(18);
        assertSame(metadata, diagnostic.filter(RECIPIENT, 2193, metadata));
        var world = new LevelChunkPacket();
        assertSame(world, diagnostic.filter(RECIPIENT, 2193, world));
    }

    @Test
    void entityRuntimeIdIsLearnedEvenWhenAddPlayerIsOmitted() {
        var diagnostic = PlayerPacketDiagnostics.create(settings("ADD_PLAYER", "SET_ENTITY_DATA"));
        assertNull(diagnostic.filter(RECIPIENT, 2193, sourceAdd()));
        var metadata = new SetEntityDataPacket();
        metadata.setRuntimeEntityId(17);
        assertNull(diagnostic.filter(RECIPIENT, 2193, metadata));
        var respawn = sourceAdd();
        respawn.setRuntimeEntityId(20);
        assertNull(diagnostic.filter(RECIPIENT, 2193, respawn));
        assertSame(metadata, diagnostic.filter(RECIPIENT, 2193, metadata));
        metadata.setRuntimeEntityId(20);
        assertNull(diagnostic.filter(RECIPIENT, 2193, metadata));
    }

    @Test
    void runtimeIdsAreNeverGuessedOrSharedBetweenConnections() {
        var first = PlayerPacketDiagnostics.create(settings("SET_ENTITY_DATA"));
        var second = PlayerPacketDiagnostics.create(settings("SET_ENTITY_DATA"));
        var metadata = new SetEntityDataPacket();
        metadata.setRuntimeEntityId(17);
        assertSame(metadata, first.filter(RECIPIENT, 2193, metadata));
        first.filter(RECIPIENT, 2193, sourceAdd());
        assertNull(first.filter(RECIPIENT, 2193, metadata));
        assertSame(metadata, second.filter(RECIPIENT, 2193, metadata));
    }

    @Test
    void reachingTheLogLimitDoesNotDisableFilteringAndSettingsAreSnapshotted() {
        var settings = settings("PLAYER_SKIN").maxLogPackets(1);
        var diagnostic = PlayerPacketDiagnostics.create(settings);
        settings.excludedPackets(List.of());
        for (int i = 0; i < 5; i++) {
            assertNull(diagnostic.filter(RECIPIENT, 2193, sourceSkin()));
        }
    }

    private static ServerSettings.PlayerPacketDiagnosticsSettings settings(String... excluded) {
        return new ServerSettings.PlayerPacketDiagnosticsSettings().enabled(true)
                .sourceUuid(SOURCE.toString()).recipientUuid(RECIPIENT.toString())
                .recipientProtocol(2193).excludedPackets(List.of(excluded));
    }

    private static AddPlayerPacket sourceAdd() {
        var packet = new AddPlayerPacket();
        packet.setUuid(SOURCE);
        packet.setRuntimeEntityId(17);
        packet.setUniqueEntityId(SOURCE.getLeastSignificantBits());
        return packet;
    }

    private static PlayerSkinPacket sourceSkin() {
        var packet = new PlayerSkinPacket();
        packet.setUuid(SOURCE);
        return packet;
    }

    private static PlayerListPacket sourceList() {
        var packet = new PlayerListPacket();
        packet.setAction(PlayerListPacket.Action.ADD);
        var entry = new PlayerListPacket.Entry(SOURCE);
        entry.setAction(PlayerListPacket.Action.ADD);
        entry.setEntityId(SOURCE.getLeastSignificantBits());
        packet.getEntries().add(entry);
        return packet;
    }
}
