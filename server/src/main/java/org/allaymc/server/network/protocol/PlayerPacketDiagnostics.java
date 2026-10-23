package org.allaymc.server.network.protocol;

import lombok.extern.slf4j.Slf4j;
import org.allaymc.server.ServerSettings;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.packet.*;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Isolates one source player's outgoing entity packets for one recipient connection.
 *
 * <p>Filtering never changes an input packet or its entries. Sequence numbers describe calls to the
 * send path, not client receipt. Each instance belongs to one connection and keeps no global state.</p>
 */
@Slf4j
public final class PlayerPacketDiagnostics {
    private static final Set<BedrockPacketType> SUPPORTED = Set.of(
            BedrockPacketType.PLAYER_LIST, BedrockPacketType.PLAYER_SKIN, BedrockPacketType.ADD_PLAYER,
            BedrockPacketType.SET_ENTITY_DATA, BedrockPacketType.MOB_ARMOR_EQUIPMENT,
            BedrockPacketType.MOB_EQUIPMENT, BedrockPacketType.UPDATE_ABILITIES,
            BedrockPacketType.UPDATE_ATTRIBUTES, BedrockPacketType.MOVE_PLAYER,
            BedrockPacketType.MOVE_ENTITY_ABSOLUTE, BedrockPacketType.MOVE_ENTITY_DELTA,
            BedrockPacketType.SET_ENTITY_MOTION, BedrockPacketType.ANIMATE,
            BedrockPacketType.ENTITY_EVENT, BedrockPacketType.REMOVE_ENTITY);
    private static final PlayerPacketDiagnostics DISABLED = new PlayerPacketDiagnostics(null, null, 0, Set.of(), 0);

    private final UUID source;
    private final UUID recipient;
    private final int recipientProtocol;
    private final Set<BedrockPacketType> excluded;
    private final int maxLogPackets;
    private Long runtimeId;
    private long uniqueId;
    private long sequence;
    private boolean announced;

    private PlayerPacketDiagnostics(UUID source, UUID recipient, int recipientProtocol,
                                    Set<BedrockPacketType> excluded, int maxLogPackets) {
        this.source = source;
        this.recipient = recipient;
        this.recipientProtocol = recipientProtocol;
        this.excluded = Set.copyOf(excluded);
        this.maxLogPackets = maxLogPackets;
        // Allay uses the UUID's least significant bits as the unique entity ID.
        this.uniqueId = source == null ? 0 : source.getLeastSignificantBits();
    }

    /** Returns the stateless pass-through diagnostic. */
    public static PlayerPacketDiagnostics disabled() {
        return DISABLED;
    }

    /**
     * Copies valid settings; malformed settings disable diagnostics without suppressing traffic.
     */
    public static PlayerPacketDiagnostics create(ServerSettings.PlayerPacketDiagnosticsSettings settings) {
        if (settings == null || !settings.enabled()) {
            return DISABLED;
        }
        try {
            var source = UUID.fromString(settings.sourceUuid());
            var recipient = UUID.fromString(settings.recipientUuid());
            if (source.equals(recipient) || settings.recipientProtocol() <= 0 || settings.maxLogPackets() <= 0) {
                throw new IllegalArgumentException("Use distinct player UUIDs, a positive protocol and a positive log limit");
            }
            var excluded = new HashSet<BedrockPacketType>();
            for (var name : settings.excludedPackets()) {
                if ("*".equals(name)) {
                    excluded.addAll(SUPPORTED);
                } else {
                    var normalized = name.trim().toUpperCase(Locale.ROOT);
                    var type = SUPPORTED.stream().filter(value -> value.name().equals(normalized))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                    "Unsupported player packet type: " + normalized));
                    excluded.add(type);
                }
            }
            return new PlayerPacketDiagnostics(source, recipient, settings.recipientProtocol(), excluded,
                    settings.maxLogPackets());
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.warn("[PLAYER-PACKET-DIAG] Disabled: {}", exception.getMessage());
            return DISABLED;
        }
    }

    /** Returns whether valid settings selected a player pair. */
    public boolean isEnabled() {
        return source != null;
    }

    /**
     * Returns the unchanged packet, a recipient-local filtered player list, or null to omit it.
     * Runtime IDs are learned from the source's AddPlayer packet, including when that packet is omitted.
     */
    public synchronized BedrockPacket filter(UUID actualRecipient, int actualProtocol, BedrockPacket packet) {
        if (!isEnabled() || !recipient.equals(actualRecipient) || recipientProtocol != actualProtocol) {
            return packet;
        }
        if (!announced) {
            announced = true;
            log.info("[PLAYER-PACKET-DIAG] source={} recipient={} protocol={} excluded={} logLimit={}; ALLOW means queued, not received",
                    source, recipient, recipientProtocol, excluded, maxLogPackets);
        }
        if (packet instanceof PlayerListPacket list) {
            var entries = list.getEntries();
            var matching = entries.stream().filter(entry -> source.equals(entry.getUuid())).toList();
            if (matching.isEmpty()) {
                return packet;
            }
            var omit = excluded.contains(BedrockPacketType.PLAYER_LIST);
            for (var entry : matching) {
                var action = entry.getAction() == null ? list.getAction() : entry.getAction();
                if (action == PlayerListPacket.Action.ADD) {
                    uniqueId = entry.getEntityId();
                }
                trace(packet, omit, "action=" + action
                        + " " + skinSummary(entry.getSkin()));
            }
            if (!omit) {
                return packet;
            }
            var filtered = new PlayerListPacket();
            filtered.setAction(list.getAction());
            entries.stream().filter(entry -> !source.equals(entry.getUuid())).forEach(filtered.getEntries()::add);
            return filtered.getEntries().isEmpty() ? null : filtered;
        }
        if (packet instanceof AddPlayerPacket add && source.equals(add.getUuid())) {
            runtimeId = add.getRuntimeEntityId();
            uniqueId = add.getUniqueEntityId();
        }
        if (!belongsToSource(packet)) {
            return packet;
        }
        var omit = excluded.contains(packet.getPacketType());
        trace(packet, omit, packet instanceof PlayerSkinPacket skin ? skinSummary(skin.getSkin()) : "");
        return omit ? null : packet;
    }

    private boolean belongsToSource(BedrockPacket packet) {
        if (packet instanceof PlayerSkinPacket skin) return source.equals(skin.getUuid());
        if (packet instanceof AddPlayerPacket add) return source.equals(add.getUuid());
        if (packet instanceof UpdateAbilitiesPacket abilities) return abilities.getUniqueEntityId() == uniqueId;
        if (packet instanceof RemoveEntityPacket remove) return remove.getUniqueEntityId() == uniqueId;
        if (runtimeId == null) return false;
        return switch (packet) {
            case SetEntityDataPacket value -> value.getRuntimeEntityId() == runtimeId;
            case MobArmorEquipmentPacket value -> value.getRuntimeEntityId() == runtimeId;
            case MobEquipmentPacket value -> value.getRuntimeEntityId() == runtimeId;
            case UpdateAttributesPacket value -> value.getRuntimeEntityId() == runtimeId;
            case MovePlayerPacket value -> value.getRuntimeEntityId() == runtimeId;
            case MoveEntityAbsolutePacket value -> value.getRuntimeEntityId() == runtimeId;
            case MoveEntityDeltaPacket value -> value.getRuntimeEntityId() == runtimeId;
            case SetEntityMotionPacket value -> value.getRuntimeEntityId() == runtimeId;
            case AnimatePacket value -> value.getRuntimeEntityId() == runtimeId;
            case EntityEventPacket value -> value.getRuntimeEntityId() == runtimeId;
            default -> false;
        };
    }

    private void trace(BedrockPacket packet, boolean omitted, String summary) {
        long current = ++sequence;
        if (current <= maxLogPackets) {
            log.info("[PLAYER-PACKET-DIAG] seq={} source={} recipient={} protocol={} type={} decision={} runtimeId={} {}",
                    current, source, recipient, recipientProtocol, packet.getPacketType(), omitted ? "OMIT" : "ALLOW", runtimeId, summary);
        } else if (current == (long) maxLogPackets + 1) {
            log.info("[PLAYER-PACKET-DIAG] recipient={} log limit reached; configured filtering remains active", recipient);
        }
    }

    private static String skinSummary(SerializedSkin skin) {
        if (skin == null) return "skin=none";
        var image = skin.getSkinData();
        var geometry = skin.getGeometryData();
        return "skin=" + (image == null ? "none" : image.getWidth() + "x" + image.getHeight())
                + " persona=" + skin.isPersona() + " trusted=" + skin.isTrusted()
                + " geometryChars=" + (geometry == null ? 0 : geometry.length())
                + " geometryHash=" + Integer.toHexString(geometry == null ? 0 : geometry.hashCode());
    }
}
