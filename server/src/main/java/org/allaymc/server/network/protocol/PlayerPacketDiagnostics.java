package org.allaymc.server.network.protocol;

import lombok.extern.slf4j.Slf4j;
import org.allaymc.server.ServerSettings;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.packet.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolates outgoing player entity packets between configured source and recipient protocols.
 *
 * <p>Filtering never changes an input packet or its entries. Sequence numbers describe calls to the
 * send path, not client receipt. Entity tracking and log limits belong to one recipient connection.</p>
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
    private static final SourceRegistry CONNECTIONS = new SourceRegistry();
    private static final PlayerPacketDiagnostics DISABLED = new PlayerPacketDiagnostics(0, 0, Set.of(), 0, CONNECTIONS);

    private final int sourceProtocol;
    private final int recipientProtocol;
    private final Set<BedrockPacketType> excluded;
    private final int maxLogPackets;
    private final SourceRegistry connections;
    private final Map<UUID, TrackedSource> knownSources = new HashMap<>();
    private final Map<Long, TrackedSource> runtimeSources = new HashMap<>();
    private final Map<Long, TrackedSource> uniqueSources = new HashMap<>();
    private long sequence;
    private boolean announced;

    private PlayerPacketDiagnostics(int sourceProtocol, int recipientProtocol, Set<BedrockPacketType> excluded,
                                    int maxLogPackets, SourceRegistry connections) {
        this.sourceProtocol = sourceProtocol;
        this.recipientProtocol = recipientProtocol;
        this.excluded = Set.copyOf(excluded);
        this.maxLogPackets = maxLogPackets;
        this.connections = connections;
    }

    /** Returns the stateless pass-through diagnostic. */
    public static PlayerPacketDiagnostics disabled() {
        return DISABLED;
    }

    /** Copies valid settings; malformed settings leave traffic unchanged. */
    public static PlayerPacketDiagnostics create(ServerSettings.PlayerPacketDiagnosticsSettings settings) {
        return create(settings, CONNECTIONS);
    }

    static PlayerPacketDiagnostics create(ServerSettings.PlayerPacketDiagnosticsSettings settings, SourceRegistry connections) {
        if (settings == null || !settings.enabled()) return DISABLED;
        try {
            if (settings.sourceProtocol() <= 0 || settings.recipientProtocol() <= 0 || settings.maxLogPackets() <= 0) {
                throw new IllegalArgumentException("Use positive source/recipient protocols and a positive log limit");
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
            return new PlayerPacketDiagnostics(settings.sourceProtocol(), settings.recipientProtocol(), excluded,
                    settings.maxLogPackets(), connections);
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.warn("[PLAYER-PACKET-DIAG] Disabled: {}", exception.getMessage());
            return DISABLED;
        }
    }

    /** Returns whether valid settings selected a protocol pair. */
    public boolean isEnabled() {
        return sourceProtocol > 0;
    }

    void registerSource(UUID uuid, int protocol, Object owner) {
        if (isEnabled()) connections.register(uuid, protocol, owner);
    }

    void unregisterSource(UUID uuid, Object owner) {
        connections.unregister(uuid, owner);
    }

    /**
     * Returns the original packet, a recipient-local player list, or null to omit it.
     * Source protocols come from negotiated connections, including players still entering the world.
     */
    public synchronized BedrockPacket filter(UUID recipient, int actualProtocol, BedrockPacket packet) {
        if (!isEnabled() || recipient == null || recipientProtocol != actualProtocol) return packet;
        if (!announced) {
            announced = true;
            log.info("[PLAYER-PACKET-DIAG] sourceProtocol={} recipient={} recipientProtocol={} excluded={} logLimit={}; ALLOW means queued, not received",
                    sourceProtocol, recipient, recipientProtocol, excluded, maxLogPackets);
        }
        if (packet instanceof PlayerListPacket list) return filterList(recipient, list);

        TrackedSource source;
        if (packet instanceof AddPlayerPacket add) {
            source = sourceFor(add.getUuid(), recipient, false);
            if (source != null) {
                recordUniqueId(source, add.getUniqueEntityId());
                if (source.runtimeId != null) runtimeSources.remove(source.runtimeId, source);
                source.runtimeId = add.getRuntimeEntityId();
                source.entityRemoved = false;
                runtimeSources.put(source.runtimeId, source);
            }
        } else if (packet instanceof PlayerSkinPacket skin) {
            source = sourceFor(skin.getUuid(), recipient, false);
        } else if (packet instanceof UpdateAbilitiesPacket abilities) {
            source = sourceByUniqueId(abilities.getUniqueEntityId(), recipient);
        } else if (packet instanceof RemoveEntityPacket remove) {
            source = sourceByUniqueId(remove.getUniqueEntityId(), recipient);
        } else {
            var runtimeId = runtimeId(packet);
            source = runtimeId == null ? null : runtimeSources.get(runtimeId);
        }
        if (source == null || !stillMatches(source, recipient)) return packet;
        var omit = excluded.contains(packet.getPacketType());
        trace(recipient, source, packet, omit,
                packet instanceof PlayerSkinPacket skin ? skinSummary(skin.getSkin()) : "");
        if (packet instanceof RemoveEntityPacket) {
            if (source.runtimeId != null) runtimeSources.remove(source.runtimeId, source);
            source.runtimeId = null;
            source.entityRemoved = true;
            if (source.listRemoved) forget(source);
        }
        return omit ? null : packet;
    }

    private BedrockPacket filterList(UUID recipient, PlayerListPacket list) {
        var kept = new ArrayList<PlayerListPacket.Entry>();
        boolean matched = false;
        var omit = excluded.contains(BedrockPacketType.PLAYER_LIST);
        for (var entry : list.getEntries()) {
            var action = entry.getAction() == null ? list.getAction() : entry.getAction();
            var source = sourceFor(entry.getUuid(), recipient, action == PlayerListPacket.Action.REMOVE);
            if (source == null) {
                kept.add(entry);
                continue;
            }
            matched = true;
            if (action == PlayerListPacket.Action.ADD) {
                recordUniqueId(source, entry.getEntityId());
                source.listRemoved = false;
            }
            trace(recipient, source, list, omit, "action=" + action + " " + skinSummary(entry.getSkin()));
            if (!omit) kept.add(entry);
            if (action == PlayerListPacket.Action.REMOVE) {
                source.listRemoved = true;
                if (source.runtimeId == null || source.entityRemoved) forget(source);
            }
        }
        if (!matched || !omit) return list;
        if (kept.isEmpty()) return null;
        var filtered = new PlayerListPacket();
        filtered.setAction(list.getAction());
        filtered.getEntries().addAll(kept);
        return filtered;
    }

    private TrackedSource sourceFor(UUID uuid, UUID recipient, boolean rememberDisconnected) {
        if (uuid == null || uuid.equals(recipient)) return null;
        int protocol = connections.protocol(uuid);
        if (protocol == sourceProtocol) {
            return knownSources.computeIfAbsent(uuid, id -> {
                var source = new TrackedSource(id);
                uniqueSources.put(source.uniqueId, source);
                return source;
            });
        }
        var known = knownSources.get(uuid);
        if (protocol > 0 && known != null) forget(known);
        return protocol == 0 && rememberDisconnected ? known : null;
    }

    private TrackedSource sourceByUniqueId(long uniqueId, UUID recipient) {
        var source = uniqueSources.get(uniqueId);
        if (source != null) return source;
        var uuid = connections.findByUniqueId(uniqueId, sourceProtocol, recipient);
        return uuid == null ? null : sourceFor(uuid, recipient, false);
    }

    private boolean stillMatches(TrackedSource source, UUID recipient) {
        int protocol = connections.protocol(source.uuid);
        if (source.uuid.equals(recipient) || (protocol > 0 && protocol != sourceProtocol)) {
            forget(source);
            return false;
        }
        return true;
    }

    private void recordUniqueId(TrackedSource source, long uniqueId) {
        uniqueSources.remove(source.uniqueId, source);
        source.uniqueId = uniqueId;
        uniqueSources.put(uniqueId, source);
    }

    private void forget(TrackedSource source) {
        knownSources.remove(source.uuid, source);
        uniqueSources.remove(source.uniqueId, source);
        if (source.runtimeId != null) runtimeSources.remove(source.runtimeId, source);
    }

    private static Long runtimeId(BedrockPacket packet) {
        return switch (packet) {
            case SetEntityDataPacket value -> value.getRuntimeEntityId();
            case MobArmorEquipmentPacket value -> value.getRuntimeEntityId();
            case MobEquipmentPacket value -> value.getRuntimeEntityId();
            case UpdateAttributesPacket value -> value.getRuntimeEntityId();
            case MovePlayerPacket value -> value.getRuntimeEntityId();
            case MoveEntityAbsolutePacket value -> value.getRuntimeEntityId();
            case MoveEntityDeltaPacket value -> value.getRuntimeEntityId();
            case SetEntityMotionPacket value -> value.getRuntimeEntityId();
            case AnimatePacket value -> value.getRuntimeEntityId();
            case EntityEventPacket value -> value.getRuntimeEntityId();
            default -> null;
        };
    }

    private void trace(UUID recipient, TrackedSource source, BedrockPacket packet, boolean omitted, String summary) {
        long current = ++sequence;
        if (current <= maxLogPackets) {
            log.info("[PLAYER-PACKET-DIAG] seq={} source={} sourceProtocol={} recipient={} recipientProtocol={} type={} decision={} runtimeId={} {}",
                    current, source.uuid, sourceProtocol, recipient, recipientProtocol, packet.getPacketType(),
                    omitted ? "OMIT" : "ALLOW", source.runtimeId, summary);
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

    private static final class TrackedSource {
        private final UUID uuid;
        private long uniqueId;
        private Long runtimeId;
        private boolean listRemoved;
        private boolean entityRemoved;

        private TrackedSource(UUID uuid) {
            this.uuid = uuid;
            // Allay derives real players' unique entity IDs from their UUIDs.
            this.uniqueId = uuid.getLeastSignificantBits();
        }
    }

    static final class SourceRegistry {
        private final Map<UUID, Registration> sources = new ConcurrentHashMap<>();

        void register(UUID uuid, int protocol, Object owner) {
            sources.put(uuid, new Registration(protocol, owner));
        }

        void unregister(UUID uuid, Object owner) {
            sources.computeIfPresent(uuid, (id, registration) -> registration.owner == owner ? null : registration);
        }

        int protocol(UUID uuid) {
            var registration = sources.get(uuid);
            return registration == null ? 0 : registration.protocol;
        }

        UUID findByUniqueId(long uniqueId, int protocol, UUID recipient) {
            return sources.entrySet().stream().filter(entry -> entry.getValue().protocol == protocol
                            && !entry.getKey().equals(recipient) && entry.getKey().getLeastSignificantBits() == uniqueId)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
        }

        private record Registration(int protocol, Object owner) { }
    }
}
