package org.allaymc.data.importer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the vetted 1.26.50 staging snapshot was actually promoted to the live data paths.
 *
 * <p>The previous version compared staging against the old 1.26.30 live data. Once the migration is promoted that
 * comparison becomes invalid by definition. The historical migration assertions remain in Git history; CI now protects
 * the promoted snapshot from partial or accidental rollbacks.</p>
 */
class StagedBedrockDataTest {

    private static final Path RESOURCES = Path.of("data/resources");
    private static final Path UNPACKED = RESOURCES.resolve("unpacked");
    private static final Path STAGING = UNPACKED.resolve("staging-1.26.50");

    @Test
    void promotedBedrockDataMatchesStagingSnapshot() throws IOException {
        var compared = new ArrayList<String>();

        try (var files = Files.walk(STAGING)) {
            for (var staged : files.filter(Files::isRegularFile).sorted().toList()) {
                var relative = STAGING.relativize(staged);
                var relativeName = relative.toString().replace('\\', '/');

                // Provenance and the local BDS oracle are not runtime files.
                if (relativeName.equals("SOURCES.md") || relativeName.equals("bds_registry_dump.json")) {
                    continue;
                }

                var live = livePath(relative);
                assertTrue(Files.isRegularFile(live), "promoted file is missing: " + live);
                assertEquals(-1L, Files.mismatch(staged, live), "staging/live mismatch: " + relativeName);
                compared.add(relativeName);
            }
        }

        assertFalse(compared.isEmpty());
        assertTrue(compared.contains("resources/block_types.json"));
        assertTrue(compared.contains("unpacked/block_palette.nbt"));
        assertTrue(compared.contains("unpacked/items_raw.json"));
    }

    private static Path livePath(Path relative) {
        if (relative.getNameCount() < 2) {
            throw new IllegalArgumentException("Unexpected staging path: " + relative);
        }

        var root = relative.getName(0).toString();
        var tail = relative.subpath(1, relative.getNameCount());
        return switch (root) {
            case "resources" -> RESOURCES.resolve(tail);
            case "unpacked" -> UNPACKED.resolve(tail);
            default -> throw new IllegalArgumentException("Unexpected staging root: " + root);
        };
    }
}
