package org.allaymc.server.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldShutdownSequenceTest {
    @Test
    void savesAfterAllDimensionsAndClosesStorageLast() {
        var calls = new ArrayList<String>();
        WorldShutdownSequence.run(List.of(() -> calls.add("overworld"), () -> calls.add("nether")),
                () -> calls.add("save"), () -> calls.add("close"));
        assertEquals(List.of("overworld","nether","save","close"),calls);
    }

    @Test
    void failedDimensionStillClosesOtherDimensionsAndStorageAndKeepsOriginalCause() {
        var original = new NullPointerException("head direction");
        var closeFailure = new IllegalStateException("close failed");
        var calls = new ArrayList<String>();
        var failure = assertThrows(IllegalStateException.class, () -> WorldShutdownSequence.run(
                List.of(() -> { throw original; }, () -> calls.add("nether")),
                () -> calls.add("save"), () -> { calls.add("close"); throw closeFailure; }));
        assertEquals(List.of("nether","close"),calls);
        assertSame(original,failure.getCause());
        assertArrayEquals(new Throwable[]{closeFailure}, original.getSuppressed());
    }

    @Test
    void failedSaveStillReleasesStorage() {
        var original = new IllegalStateException("write failed");
        var calls = new ArrayList<String>();
        var failure = assertThrows(IllegalStateException.class, () -> WorldShutdownSequence.run(
                List.of(), () -> { throw original; }, () -> calls.add("close")));
        assertSame(original,failure.getCause());
        assertEquals(List.of("close"),calls);
    }
}
