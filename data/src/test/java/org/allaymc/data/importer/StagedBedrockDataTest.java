package org.allaymc.data.importer;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.26.50 staging stays immutable, but the active vanilla registry is 1.26.60.
 * Do not insist that the newer runtime blocks/items equal the older snapshot.
 */
class StagedBedrockDataTest {
    private static final Path RESOURCES = Path.of("data/resources");
    private static final Path UNPACKED = RESOURCES.resolve("unpacked");
    private static final Path STAGING = UNPACKED.resolve("staging-1.26.50");
    private static final Set<String> UPDATED = Set.of(
            "resources/block_types.json", "resources/items.json", "resources/recipes.json",
            "resources/creative_items.nbt", "resources/creative_groups.json",
            "resources/item_components.nbt", "unpacked/block_palette.nbt",
            "unpacked/block_states_raw.json", "unpacked/items_raw.json",
            "unpacked/item_tags.json", "unpacked/block_tags.json");

    @Test
    void historicalSnapshotAndUnchangedAssetsRemainStable() throws IOException {
        assertTrue(Files.isRegularFile(STAGING.resolve("resources/block_types.json")));
        assertTrue(Files.isRegularFile(STAGING.resolve("unpacked/items_raw.json")));
        var compared = new ArrayList<String>();
        try (var files = Files.walk(STAGING)) {
            for (var staged : files.filter(Files::isRegularFile).sorted().toList()) {
                var relative = STAGING.relativize(staged);
                var name = relative.toString().replace('\\', '/');
                if (name.equals("SOURCES.md") || name.equals("bds_registry_dump.json") || UPDATED.contains(name)) {
                    continue;
                }
                var live = switch (relative.getName(0).toString()) {
                    case "resources" -> RESOURCES.resolve(relative.subpath(1, relative.getNameCount()));
                    case "unpacked" -> UNPACKED.resolve(relative.subpath(1, relative.getNameCount()));
                    default -> throw new IllegalArgumentException("Unknown staging root: " + relative);
                };
                assertTrue(Files.isRegularFile(live), "Missing historical asset " + live);
                assertEquals(-1L, Files.mismatch(staged, live), "Changed historical asset " + name);
                compared.add(name);
            }
        }
        assertFalse(compared.isEmpty(), "Historical snapshot has no unchanged files");
        assertTrue(compared.contains("resources/biome_definitions.json"));
    }
}
