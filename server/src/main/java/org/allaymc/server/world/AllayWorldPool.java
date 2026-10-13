package org.allaymc.server.world;

import com.google.common.base.Preconditions;
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.OkaeriConfigInitializer;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.CustomKey;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.eventbus.event.world.WorldGeneratorInitEvent;
import org.allaymc.api.eventbus.event.world.WorldLoadEvent;
import org.allaymc.api.eventbus.event.world.WorldUnloadEvent;
import org.allaymc.api.message.I18n;
import org.allaymc.api.message.TrKeys;
import org.allaymc.api.registry.Registries;
import org.allaymc.api.utils.identifier.Identifier;
import org.allaymc.api.world.Dimension;
import org.allaymc.api.world.World;
import org.allaymc.api.world.WorldPool;
import org.allaymc.api.world.WorldState;
import org.allaymc.api.world.dimension.DimensionType;
import org.allaymc.api.world.dimension.DimensionTypes;
import org.allaymc.api.world.generator.WorldGenerator;
import org.allaymc.server.world.dimension.BuiltinDimensionSettings;
import org.allaymc.server.world.dimension.DimensionId;
import org.allaymc.server.world.light.NoLightEngine;
import org.allaymc.server.world.storage.DimensionWorldStorage;
import org.allaymc.server.world.storage.DimensionStorageInspector;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author daoge_cmd
 */
@Slf4j
public final class AllayWorldPool implements WorldPool {

    private final Map<String, AllayWorld> worlds;
    @Getter
    private final Path worldFolder;
    private final WorldSettings worldConfig;

    @Setter
    private World defaultWorld;

    public AllayWorldPool() {
        this.worlds = new ConcurrentHashMap<>();
        this.worldFolder = Path.of("worlds");
        var builtinDimensionSettings = BuiltinDimensionSettings.get();
        this.worldConfig = Objects.requireNonNull(ConfigManager.create(
                WorldSettings.class,
                createWorldSettingsInitializer(this.worldFolder.resolve("world-settings.yml"))
        ));
        this.worldConfig.normalize();
        applyBuiltinDimensionSettings(builtinDimensionSettings);
        this.worldConfig.save();
        BuiltinDimensionSettings.useWorldSettings(this.worldConfig);
    }

    public void loadWorlds() {
        this.worldConfig.worlds().forEach((name, setting) -> {
            try {
                loadWorld(name, setting);
            } catch (Exception e) {
                log.error("Error when loading world {}, skipped.", name, e);
            }
        });
    }

    public void shutdown() {
        this.worlds.values().forEach(AllayWorld::shutdown);

        // Waiting for all worlds to fully shutdown
        while (this.worlds.values().stream().anyMatch(world -> world.getState() != WorldState.STOPPED)) {
            Thread.onSpinWait();
        }

        // Preserve configured worlds even if they were unloaded before shutdown.
        // Their definitions must not disappear from world-settings.yml.
        var persistedWorlds = new LinkedHashMap<>(this.worldConfig.worlds());
        this.worlds.forEach((name, world) -> {
            if (!world.isRuntimeOnly()) {
                var configured = persistedWorlds.get(name);
                // Keep the configured storage layout and disabled dimensions intact.
                // Runtime generator changes can be persisted for legacy shared worlds only.
                if (configured == null) {
                    persistedWorlds.put(name, toWorldSetting(world));
                }
                // Existing configured worlds retain their exact definitions, including
                // dimensions disabled for maintenance and future storage paths.
            }
        });
        this.worldConfig.worlds().clear();
        this.worldConfig.worlds().putAll(persistedWorlds);
        applyBuiltinDimensionSettings(BuiltinDimensionSettings.get());
        this.worldConfig.save();
    }

    @Override
    public void loadWorld(WorldSetting worldSetting, Map<Identifier, DimensionSetting> dimensionSettings) {
        var name = worldSetting.name();
        log.info(I18n.get().tr(TrKeys.ALLAY_WORLD_LOADING, name));
        if (worlds.containsKey(name)) {
            throw new IllegalArgumentException("World " + name + " is already loaded");
        }

        var validatedDimensionSettings = new LinkedHashMap<Identifier, DimensionSetting>();
        for (var entry : dimensionSettings.entrySet()) {
            var identifier = entry.getKey();
            var dimensionSetting = Preconditions.checkNotNull(entry.getValue(), "Dimension setting for %s cannot be null", identifier);
            validateDimensionType(dimensionSetting.dimensionType());
            Preconditions.checkArgument(
                    identifier.equals(dimensionSetting.dimensionType().getIdentifier()),
                    "Dimension setting key %s does not match dimension type identifier %s",
                    identifier,
                    dimensionSetting.dimensionType().getIdentifier()
            );
            Preconditions.checkArgument(
                    validatedDimensionSettings.put(identifier, dimensionSetting) == null,
                    "Duplicate dimension setting for %s",
                    identifier
            );
        }

        var useVirtualThread = worldSetting.useVirtualThread();
        AllayWorld world;
        try {
            world = new AllayWorld(name, worldSetting.storage(), useVirtualThread);
        } catch (Throwable t) {
            log.error("Error while initializing world {}", name, t);
            return;
        }

        var overworldSetting = Preconditions.checkNotNull(
                validatedDimensionSettings.get(DimensionTypes.OVERWORLD.getIdentifier()),
                "World must have the overworld dimension"
        );
        loadDimension(world, overworldSetting, useVirtualThread);
        for (var setting : validatedDimensionSettings.values()) {
            if (setting.dimensionType() == DimensionTypes.OVERWORLD) {
                continue;
            }
            loadDimension(world, setting, useVirtualThread);
        }

        var event = new WorldLoadEvent(world);
        if (!event.call()) {
            return;
        }

        this.worlds.put(world.getName(), world);
        world.startTick();
        log.info(I18n.get().tr(TrKeys.ALLAY_WORLD_LOADED, name));
    }

    @Override
    public void unloadWorld(String name) {
        var world = this.worlds.get(name);
        if (world == null) {
            throw new IllegalArgumentException("World " + name + " not found");
        }
        if (world == getDefaultWorld()) {
            throw new IllegalArgumentException("Cannot unload default world");
        }

        var event = new WorldUnloadEvent(world);
        if (!event.call()) {
            return;
        }

        world.shutdown();
        this.worlds.remove(name);
    }

    @Override
    public World getWorld(String name) {
        return this.worlds.get(name);
    }

    @Override
    public Map<String, World> getWorlds() {
        return Collections.unmodifiableMap(this.worlds);
    }

    @Override
    public World getDefaultWorld() {
        if (defaultWorld == null) defaultWorld = getWorld(worldConfig.defaultWorld());
        return defaultWorld;
    }

    private void loadWorld(String name, WorldSettings.WorldSetting setting) {
        // A separate layout must never silently fall back to shared storage: this could
        // create or overwrite chunks in the wrong database before conversion is ready.
        var separate = "SEPARATE".equalsIgnoreCase(setting.dimensionStorageMode());
        Preconditions.checkArgument(
                separate || "SHARED".equalsIgnoreCase(setting.dimensionStorageMode()),
                "Unknown dimension storage mode %s for world %s", setting.dimensionStorageMode(), name
        );
        var storageFactory = Registries.WORLD_STORAGE_FACTORIES.get(setting.storageType());
        if (storageFactory == null) {
            log.warn("Unknown world storage type {} for {}, falling back to LEVELDB", setting.storageType(), name);
            storageFactory = Preconditions.checkNotNull(Registries.WORLD_STORAGE_FACTORIES.get("LEVELDB"), "LEVELDB storage factory unavailable");
        }
        // Preflight every configured path *before* opening any DB. Opening LevelDB
        // with createIfMissing=true could otherwise silently generate empty chunks.
        var perDimension = new LinkedHashMap<DimensionType, org.allaymc.api.world.storage.WorldStorage>();
        var uniquePaths = new java.util.HashSet<Path>();
        var configuredDimensions = setting.normalizedDimensions();
        var root = this.worldFolder.toAbsolutePath().normalize();
        if (separate) {
            for (var entry : configuredDimensions.entrySet()) {
                if (!entry.getValue().enabled()) continue;
                var type = Preconditions.checkNotNull(Registries.DIMENSIONS.getByK2(entry.getKey()), "Unknown dimension %s", entry.getKey());
                var configuredPath = entry.getValue().path();
                var folderName = configuredPath == null || configuredPath.isBlank()
                        ? (type == DimensionTypes.OVERWORLD ? name : type == DimensionTypes.NETHER ? "nether" : "end")
                        : configuredPath;
                var path = root.resolve(folderName).normalize();
                Preconditions.checkArgument(path.startsWith(root) && !path.equals(root), "Unsafe dimension path: %s", folderName);
                Preconditions.checkArgument(uniquePaths.add(path), "Duplicate dimension path %s", path);
                Preconditions.checkArgument(setting.storageType().equalsIgnoreCase("LEVELDB"), "SEPARATE requires LEVELDB storage");
                Preconditions.checkArgument(!java.nio.file.Files.isSymbolicLink(path), "Symbolic links are not supported for dimension paths: %s", path);
                DimensionStorageInspector.requireNativeLayout(path, type.getId());
            }
        }
        var storage = separate ? null : Preconditions.checkNotNull(storageFactory.apply(this.worldFolder.resolve(name)), "Failed to create storage for %s", name);
        try {
            if (separate) {
                for (var entry : configuredDimensions.entrySet()) {
                    if (!entry.getValue().enabled()) continue;
                    var type = Registries.DIMENSIONS.getByK2(entry.getKey());
                    var configuredPath = entry.getValue().path();
                    var folderName = configuredPath == null || configuredPath.isBlank()
                            ? (type == DimensionTypes.OVERWORLD ? name : type == DimensionTypes.NETHER ? "nether" : "end")
                            : configuredPath;
                    perDimension.put(type, storageFactory.apply(root.resolve(folderName).normalize()));
                }
                var primary = Preconditions.checkNotNull(perDimension.get(DimensionTypes.OVERWORLD), "Overworld must be enabled");
                storage = new DimensionWorldStorage(primary, perDimension);
            }
        } catch (RuntimeException e) {
            perDimension.values().forEach(open -> {
                try { open.shutdown(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            });
            throw e;
        }

        var dimensionSettings = new LinkedHashMap<Identifier, DimensionSetting>();
        for (var entry : setting.normalizedDimensions().entrySet()) {
            if (!entry.getValue().enabled()) continue;
            var dimensionType = Registries.DIMENSIONS.getByK2(entry.getKey());
            Preconditions.checkArgument(dimensionType != null, "Dimension type %s is not registered", entry.getKey());
            dimensionSettings.put(
                    entry.getKey(),
                    new DimensionSetting(
                            dimensionType,
                            tryCreateWorldGenerator(entry.getValue()),
                            entry.getValue().enableLightCalculation()
                    )
            );
        }

        try {
            loadWorld(
                    new WorldSetting(name, storage, setting.useVirtualThread),
                    dimensionSettings
            );
        } catch (RuntimeException | Error e) {
            try { storage.shutdown(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private WorldGenerator tryCreateWorldGenerator(WorldSettings.WorldSetting.DimensionSetting settings) {
        var factory = Registries.WORLD_GENERATOR_FACTORIES.get(settings.generatorType());
        if (factory == null) {
            log.error("Cannot find world generator {}, fallback to the VOID generator", settings.generatorType());
            factory = Registries.WORLD_GENERATOR_FACTORIES.get("VOID");
        }

        try {
            return factory.apply(settings.generatorPreset());
        } catch (Throwable t) {
            log.error("Error while creating {} type world generator with the preset {}, fallback to the VOID generator", settings.generatorType(), settings.generatorPreset(), t);
            return Registries.WORLD_GENERATOR_FACTORIES.get("VOID").apply(null);
        }
    }

    private void loadDimension(AllayWorld world, DimensionSetting dimensionSetting, boolean useVirtualThread) {
        new WorldGeneratorInitEvent(world, dimensionSetting.worldGenerator(), dimensionSetting.dimensionType()).call();
        world.addDimension(new AllayDimension(
                world,
                dimensionSetting.worldGenerator(),
                dimensionSetting.dimensionType(),
                dimensionSetting.enableLightCalculation(),
                useVirtualThread
        ));
    }

    private WorldSettings.WorldSetting toWorldSetting(World world) {
        var dimensions = new LinkedHashMap<String, WorldSettings.WorldSetting.DimensionSetting>();
        var overworld = world.getOverWorld();
        if (overworld != null) {
            dimensions.put(
                    DimensionId.OVERWORLD.getIdentifier().toString(),
                    toWorldDimensionSetting(overworld)
            );
        }
        for (var entry : world.getDimensions().entrySet()) {
            if (entry.getKey() == DimensionTypes.OVERWORLD) {
                continue;
            }
            dimensions.put(entry.getKey().getIdentifier().toString(), toWorldDimensionSetting(entry.getValue()));
        }

        return new WorldSettings.WorldSetting(
                world.getWorldStorage().getName(),
                world.isVirtualTickingThread(),
                dimensions
        );
    }

    private WorldSettings.WorldSetting.DimensionSetting toWorldDimensionSetting(Dimension dimension) {
        var generator = dimension.getWorldGenerator();
        var lightEnabled = !(((AllayDimension) dimension).getLightEngine() instanceof NoLightEngine);
        return new WorldSettings.WorldSetting.DimensionSetting(generator.getName(), generator.getPreset(), lightEnabled);
    }

    private static void validateDimensionType(DimensionType dimensionType) {
        var minHeight = dimensionType.getMinHeight();
        var maxHeight = dimensionType.getMaxHeight();
        Preconditions.checkArgument(minHeight >= -512 && minHeight <= 512, "Dimension min height %s is out of range", minHeight);
        Preconditions.checkArgument(maxHeight >= -512 && maxHeight <= 512, "Dimension max height %s is out of range", maxHeight);
        Preconditions.checkArgument(maxHeight >= minHeight, "Dimension max height %s is smaller than min height %s", maxHeight, minHeight);
        Preconditions.checkArgument((minHeight & 15) == 0, "Dimension min height %s must align to section boundaries", minHeight);
        Preconditions.checkArgument((maxHeight & 15) == 15, "Dimension max height %s must align to section boundaries", maxHeight);
        Preconditions.checkArgument(
                dimensionType.chunkSectionCount() == dimensionType.maxSectionY() - dimensionType.minSectionY() + 1,
                "Dimension %s has inconsistent section metadata",
                dimensionType.getIdentifier()
        );
    }

    private static Identifier normalizeDimensionIdentifier(String rawIdentifier) {
        return DimensionId.normalizeConfigIdentifier(rawIdentifier);
    }

    private static OkaeriConfigInitializer createWorldSettingsInitializer(Path path) {
        return it -> {
            it.withConfigurer(new eu.okaeri.configs.yaml.snakeyaml.YamlSnakeYamlConfigurer());
            it.withBindFile(path);
            it.withRemoveOrphans(true);
            it.saveDefaults();
            it.load();
        };
    }

    private void applyBuiltinDimensionSettings(BuiltinDimensionSettings builtinDimensionSettings) {
        var builtinDimensions = new LinkedHashMap<String, WorldSettings.BuiltinDimensionSetting>();
        for (var dimensionId : DimensionId.values()) {
            var range = builtinDimensionSettings.getHeightRange(dimensionId);
            builtinDimensions.put(
                    dimensionId.getIdentifier().toString(),
                    new WorldSettings.BuiltinDimensionSetting(range.minHeight(), range.maxHeight())
            );
        }
        this.worldConfig.builtinDimensions(builtinDimensions);
    }

    @Getter
    @Accessors(fluent = true)
    public static class WorldSettings extends OkaeriConfig {

        @Comment("Height settings shared by the built-in dimensions in all worlds")
        @Setter
        @CustomKey("built-in-dimensions")
        private Map<String, BuiltinDimensionSetting> builtinDimensions = new LinkedHashMap<>(Map.of(
                DimensionId.OVERWORLD.getIdentifier().toString(),
                new BuiltinDimensionSetting(
                        DimensionId.OVERWORLD.getDefaultMinHeight(),
                        DimensionId.OVERWORLD.getDefaultMaxHeight()
                ),
                DimensionId.NETHER.getIdentifier().toString(),
                new BuiltinDimensionSetting(
                        DimensionId.NETHER.getDefaultMinHeight(),
                        DimensionId.NETHER.getDefaultMaxHeight()
                ),
                DimensionId.THE_END.getIdentifier().toString(),
                new BuiltinDimensionSetting(
                        DimensionId.THE_END.getDefaultMinHeight(),
                        DimensionId.THE_END.getDefaultMaxHeight()
                )
        ));

        @Setter
        @CustomKey("worlds")
        private Map<String, WorldSetting> worlds = Map.of("world", new WorldSetting(
                "LEVELDB",
                false,
                new LinkedHashMap<>(Map.of(
                        DimensionId.OVERWORLD.getIdentifier().toString(),
                        new WorldSetting.DimensionSetting("FLAT", "")
                ))
        ));

        @Comment("The default world is the world that newly joined players will be in")
        @CustomKey("default-world")
        private String defaultWorld = "world";

        public Map<DimensionId, BuiltinDimensionSetting> normalizedBuiltinDimensions() {
            var normalized = new LinkedHashMap<DimensionId, BuiltinDimensionSetting>();
            if (builtinDimensions == null || builtinDimensions.isEmpty()) {
                return normalized;
            }

            builtinDimensions.forEach((identifier, setting) -> {
                Preconditions.checkNotNull(setting, "Built-in dimension setting %s cannot be null", identifier);
                var dimensionId = DimensionId.fromIdentifier(normalizeDimensionIdentifier(identifier));
                Preconditions.checkArgument(dimensionId != null, "Built-in dimension setting %s is not a built-in dimension", identifier);
                Preconditions.checkArgument(
                        normalized.put(dimensionId, setting) == null,
                        "Duplicate built-in dimension setting for %s",
                        dimensionId.getIdentifier()
                );
            });
            return normalized;
        }

        public void normalize() {
            var normalizedBuiltinDimensions = new LinkedHashMap<String, BuiltinDimensionSetting>();
            normalizedBuiltinDimensions().forEach((dimensionId, setting1) -> normalizedBuiltinDimensions.put(dimensionId.getIdentifier().toString(), setting1));
            this.builtinDimensions = normalizedBuiltinDimensions;

            var normalizedWorlds = new LinkedHashMap<String, WorldSetting>();
            if (worlds != null && !worlds.isEmpty()) {
                worlds.forEach((name, setting) -> {
                    Preconditions.checkNotNull(setting, "World setting %s cannot be null", name);
                    normalizedWorlds.put(name, setting.normalized());
                });
            }
            this.worlds = normalizedWorlds;
        }

        @Getter
        @Accessors(fluent = true)
        @AllArgsConstructor
        public static class BuiltinDimensionSetting extends OkaeriConfig {
            @CustomKey("min-height")
            private int minHeight;

            @CustomKey("max-height")
            private int maxHeight;
        }

        @Getter
        @Accessors(fluent = true)
        public static class WorldSetting extends OkaeriConfig {
            @CustomKey("storage-type")
            private String storageType;

            @Comment("If set to true, virtual thread will be used as the ticking thread of the world and the dimensions in the world")
            @CustomKey("use-virtual-thread")
            private boolean useVirtualThread;

            @Setter
            @CustomKey("dimension-storage-mode")
            @Comment("SHARED keeps all dimensions in the same LevelDB. SEPARATE requires the conversion engine before activation.")
            private String dimensionStorageMode = "SHARED";

            private Map<String, DimensionSetting> dimensions;

            private DimensionSetting overworld;

            // Can be null
            private DimensionSetting nether;

            // Can be null
            @CustomKey("the-end")
            private DimensionSetting theEnd;

            public WorldSetting(String storageType, boolean useVirtualThread, Map<String, DimensionSetting> dimensions) {
                this(storageType, useVirtualThread, dimensions, null, null, null);
            }

            public WorldSetting(
                    String storageType,
                    boolean useVirtualThread,
                    Map<String, DimensionSetting> dimensions,
                    DimensionSetting overworld,
                    DimensionSetting nether,
                    DimensionSetting theEnd
            ) {
                this.storageType = storageType;
                this.useVirtualThread = useVirtualThread;
                this.dimensions = dimensions;
                this.overworld = overworld;
                this.nether = nether;
                this.theEnd = theEnd;
            }

            public Map<Identifier, DimensionSetting> normalizedDimensions() {
                var normalized = new LinkedHashMap<Identifier, DimensionSetting>();
                if (dimensions != null && !dimensions.isEmpty()) {
                    dimensions.forEach((identifier, setting) -> {
                        Preconditions.checkNotNull(setting, "Dimension setting %s cannot be null", identifier);
                        var normalizedIdentifier = normalizeDimensionIdentifier(identifier);
                        Preconditions.checkArgument(
                                normalized.put(normalizedIdentifier, setting) == null,
                                "Duplicate dimension setting for %s",
                                normalizedIdentifier
                        );
                    });
                    return normalized;
                }

                if (overworld != null) {
                    normalized.put(DimensionId.OVERWORLD.getIdentifier(), overworld);
                }
                if (nether != null) {
                    normalized.put(DimensionId.NETHER.getIdentifier(), nether);
                }
                if (theEnd != null) {
                    normalized.put(DimensionId.THE_END.getIdentifier(), theEnd);
                }
                return normalized;
            }

            public WorldSetting normalized() {
                var normalizedDimensions = new LinkedHashMap<String, DimensionSetting>();
                normalizedDimensions().forEach((identifier, setting) -> normalizedDimensions.put(identifier.toString(), setting));
                var normalizedWorld = new WorldSetting(storageType, useVirtualThread, normalizedDimensions);
                normalizedWorld.dimensionStorageMode(dimensionStorageMode == null ? "SHARED" : dimensionStorageMode.trim().toUpperCase(java.util.Locale.ROOT));
                return normalizedWorld;
            }

            @Getter
            @Accessors(fluent = true)
            public static class DimensionSetting extends OkaeriConfig {
                @CustomKey("generator-type")
                private String generatorType;

                @CustomKey("generator-preset")
                private String generatorPreset;

                @Comment("If set to false, light calculation will be disabled for this dimension")
                @Comment("All light queries will return max value (15)")
                @CustomKey("enable-light-calculation")
                private boolean enableLightCalculation = true;

                @Setter
                private boolean enabled = true;

                @Setter
                @CustomKey("path")
                private String path;

                public DimensionSetting(String generatorType, String generatorPreset) {
                    this(generatorType, generatorPreset, true);
                }

                public DimensionSetting(String generatorType, String generatorPreset, boolean enableLightCalculation) {
                    this.generatorType = generatorType;
                    this.generatorPreset = generatorPreset;
                    this.enableLightCalculation = enableLightCalculation;
                }
            }
        }
    }
}
