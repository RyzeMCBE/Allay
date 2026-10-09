package org.allaymc.api.player;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.registry.Registries;
import org.allaymc.api.server.Server;
import org.allaymc.api.utils.identifier.Identifier;
import org.allaymc.api.world.dimension.DimensionTypes;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

import java.util.EnumSet;
import java.util.Objects;

import static org.allaymc.api.utils.AllayNBTUtils.writeVector2f;
import static org.allaymc.api.utils.AllayNBTUtils.writeVector3f;

/**
 * PlayerData represents the entry stores in {@link PlayerStorage}. It includes the player's nbt, the world, and
 * the dimension that the player is currently in.
 *
 * @author daoge_cmd
 */
@Slf4j
@Getter
@Setter
@Builder
public class PlayerData {

    protected static final String TAG_NBT = "NBT";
    protected static final String TAG_WORLD = "World";
    protected static final String TAG_DIMENSION = "Dimension";
    protected static final String TAG_ABILITIES = "Abilities";

    // EntityPlayer's nbt, which can be generated through the method EntityPlayer#saveNBT()
    protected NbtMap nbt;

    // The following fields are not included in the return object of method EntityPlayer#saveNBT()
    // We should store the world and dimension information of the player, because the player data
    // is not stored in chunk like other entities. Without this information, we can't know which
    // world and dimension the player is in.
    protected String world;
    protected String dimension;
    protected EnumSet<PlayerAbility> abilities;

    public static PlayerData save(Player player) {
        var entity = player.getControlledEntity();
        if (entity == null) {
            log.warn("Player is not controlling any entity!");
            return PlayerData.createEmpty();
        }

        return PlayerData.builder()
                .nbt(entity.saveNBT())
                // WorldPool is keyed by World#getName(), not by WorldData#getDisplayName().
                // Persisting the display name makes the next login look like the world no longer exists,
                // which causes AllayPlayer to replace only Pos with the global spawn point.
                .world(entity.getWorld().getName())
                .dimension(entity.getDimension().getDimensionType().getIdentifier().toString())
                .abilities(player.getAbilities().isEmpty() ? EnumSet.noneOf(PlayerAbility.class) : EnumSet.copyOf(player.getAbilities()))
                .build();
    }

    /**
     * Creates an empty player data entry.
     *
     * @return an empty player data entry
     */
    public static PlayerData createEmpty() {
        var server = Server.getInstance();
        var globalSpawnPoint = server.getWorldPool().getGlobalSpawnPoint();
        var builder = NbtMap.builder();
        writeVector3f(builder, "Pos", globalSpawnPoint.x(), globalSpawnPoint.y(), globalSpawnPoint.z());
        writeVector2f(builder, "Rotation", 0f, 0f);
        var worldName = globalSpawnPoint.dimension().getWorld().getName();
        var dimensionId = globalSpawnPoint.dimension().getDimensionType().getIdentifier().toString();
        return builder()
                .nbt(builder.build())
                .world(worldName)
                .dimension(dimensionId)
                .build();
    }

    /**
     * Creates a {@link PlayerData} object from a nbt.
     *
     * @param nbt the nbt that holds the data
     * @return a {@link PlayerData} object
     */
    public static PlayerData fromNBT(NbtMap nbt) {
        var builder = builder();
        builder.nbt(nbt.getCompound(TAG_NBT))
                .world(normalizeStoredWorldName(nbt.getString(TAG_WORLD)))
                .dimension(readDimension(nbt));

        if (nbt.containsKey(TAG_ABILITIES)) {
            var abilities = EnumSet.noneOf(PlayerAbility.class);
            for (var abilityName : nbt.getList(TAG_ABILITIES, NbtType.STRING)) {
                try {
                    abilities.add(PlayerAbility.valueOf(abilityName));
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown stored player ability {}, ignoring it", abilityName);
                }
            }
            builder.abilities(abilities);
        }

        return builder.build();
    }

    /**
     * Resolves player data written by older Allay builds which persisted the world's display name instead of
     * the internal world name. WorldPool#getWorld(String) only accepts the internal name, so leaving the old
     * value untouched makes AllayPlayer treat an existing world as missing and replace the player's Pos with
     * the global spawn point. A unique display-name match is safe to migrate in memory; the next normal save
     * writes the canonical internal name.
     */
    protected static String normalizeStoredWorldName(String storedWorldName) {
        var worldPool = Server.getInstance().getWorldPool();
        if (worldPool.getWorld(storedWorldName) != null) {
            return storedWorldName;
        }

        String resolvedWorldName = null;
        for (var world : worldPool.getWorlds().values()) {
            if (!Objects.equals(world.getWorldData().getDisplayName(), storedWorldName)) {
                continue;
            }

            if (resolvedWorldName != null && !Objects.equals(resolvedWorldName, world.getName())) {
                log.warn("Stored player world '{}' matches multiple world display names; keeping legacy reference", storedWorldName);
                return storedWorldName;
            }
            resolvedWorldName = world.getName();
        }

        if (resolvedWorldName != null) {
            log.debug("Resolved legacy player world display name '{}' to world '{}'", storedWorldName, resolvedWorldName);
            return resolvedWorldName;
        }

        return storedWorldName;
    }

    /**
     * Saves this {@link PlayerData} object to a nbt.
     *
     * @return the saved nbt
     */
    public NbtMap toNBT() {
        var builder = NbtMap.builder()
                .putCompound(TAG_NBT, nbt)
                .putString(TAG_WORLD, world)
                .putString(TAG_DIMENSION, dimension);

        if (abilities != null) {
            builder.putList(TAG_ABILITIES, NbtType.STRING, abilities.stream().map(Enum::name).toList());
        }

        return builder.build();
    }

    protected static String readDimension(NbtMap nbt) {
        var dimension = nbt.get(TAG_DIMENSION);
        return switch (dimension) {
            case String id -> new Identifier(id).toString();
            case Number id -> {
                var dimensionType = Registries.DIMENSIONS.getByK1(id.intValue());
                yield (dimensionType != null ? dimensionType : DimensionTypes.OVERWORLD).getIdentifier().toString();
            }
            case null, default -> DimensionTypes.OVERWORLD.getIdentifier().toString();
        };
    }
}
