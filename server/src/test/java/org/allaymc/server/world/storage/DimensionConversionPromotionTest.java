package org.allaymc.server.world.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import static org.junit.jupiter.api.Assertions.*;

class DimensionConversionPromotionTest {
    @TempDir Path root;

    @Test
    void restoresOriginalWhenCrashOccursAfterFirstRename() throws Exception {
        var world = root.resolve("end");
        Files.createDirectories(world);
        Files.writeString(world.resolve("original.txt"), "original");
        var journal = DimensionConversionPromotion.journalPath(world);
        Files.writeString(journal, DimensionConversionPromotion.journalContents(world));
        Files.move(world, DimensionConversionPromotion.previousPath(world), StandardCopyOption.ATOMIC_MOVE);
        assertTrue(DimensionConversionPromotion.recoverIfInterrupted(world));
        assertEquals("original", Files.readString(world.resolve("original.txt")));
        assertFalse(Files.exists(journal));
        assertFalse(DimensionConversionPromotion.recoverIfInterrupted(world));
    }

    @Test
    void restoresOriginalAndRetainsFailedCandidateAfterSecondRename() throws Exception {
        var world = root.resolve("end");
        var previous = DimensionConversionPromotion.previousPath(world);
        Files.createDirectories(world);
        Files.writeString(world.resolve("converted.txt"), "new");
        Files.createDirectories(previous);
        Files.writeString(previous.resolve("original.txt"), "old");
        Files.writeString(DimensionConversionPromotion.journalPath(world),
                DimensionConversionPromotion.journalContents(world));
        assertTrue(DimensionConversionPromotion.recoverIfInterrupted(world));
        assertEquals("old", Files.readString(world.resolve("original.txt")));
        assertEquals("new", Files.readString(DimensionConversionPromotion.failedPath(world).resolve("converted.txt")));
    }

    @Test
    void refusesUnknownJournalWithoutMovingFiles() throws Exception {
        var world = root.resolve("end");
        Files.createDirectories(world);
        Files.writeString(DimensionConversionPromotion.journalPath(world), "unrecognized");
        assertThrows(RuntimeException.class, () -> DimensionConversionPromotion.recoverIfInterrupted(world));
        assertTrue(Files.isDirectory(world));
    }
}
