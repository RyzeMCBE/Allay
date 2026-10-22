package org.allaymc.server.world;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ParallelDimensionTickDiagnosticsTest {
    private static final long START = 1_000_000_000L;

    @Test
    void reportsOnlyAfterEightSecondsAndOnlyOncePerTick() {
        try (var executor = Executors.newSingleThreadExecutor();
             var diagnostics = new ParallelDimensionTickDiagnostics("test", Thread.currentThread(), executor)) {
            var tick = diagnostics.beginTick(120L, START);
            diagnostics.dimensionPhase("minecraft:nether", "entities-and-physics");

            assertNull(diagnostics.claimStalledTick(START + ParallelDimensionTickDiagnostics.STALL_THRESHOLD_NANOS - 1));
            assertSame(tick, diagnostics.claimStalledTick(START + ParallelDimensionTickDiagnostics.STALL_THRESHOLD_NANOS));
            assertNull(diagnostics.claimStalledTick(START + ParallelDimensionTickDiagnostics.STALL_THRESHOLD_NANOS + 1));
            assertEquals("entities-and-physics", tick.phases.get("minecraft:nether").stage());
            assertSame(Thread.currentThread(), tick.phases.get("minecraft:nether").thread());
        }
    }

    @Test
    void completedTickCannotBeReportedAndNextTickResetsObservation() {
        try (var executor = Executors.newSingleThreadExecutor();
             var diagnostics = new ParallelDimensionTickDiagnostics("test", Thread.currentThread(), executor)) {
            var first = diagnostics.beginTick(1L, START);
            diagnostics.completeTick(first);
            assertNull(diagnostics.claimStalledTick(START + 20_000_000_000L));

            var next = diagnostics.beginTick(2L, START + 30_000_000_000L);
            assertNull(diagnostics.claimStalledTick(START + 30_000_000_001L));
            assertSame(next, diagnostics.claimStalledTick(START + 38_000_000_000L));
        }
    }

    @Test
    void oldTaskCannotClearNewTickObservation() {
        try (var executor = Executors.newSingleThreadExecutor();
             var diagnostics = new ParallelDimensionTickDiagnostics("test", Thread.currentThread(), executor)) {
            var old = diagnostics.beginTick(1L, START);
            var current = diagnostics.beginTick(2L, START + 20_000_000_000L);
            diagnostics.completeTick(old);

            assertSame(current, diagnostics.claimStalledTick(START + 28_000_000_000L));
            diagnostics.completeTick(current);
            assertNull(diagnostics.claimStalledTick(START + 50_000_000_000L));
        }
    }

    @Test
    void tracksIndependentDimensionStages() {
        try (var executor = Executors.newSingleThreadExecutor();
             var diagnostics = new ParallelDimensionTickDiagnostics("test", Thread.currentThread(), executor)) {
            var tick = diagnostics.beginTick(1L, START);
            diagnostics.dimensionPhase("minecraft:overworld", "chunk-manager");
            diagnostics.dimensionPhase("minecraft:nether", "scheduler");
            assertEquals("chunk-manager", tick.phases.get("minecraft:overworld").stage());
            assertEquals("scheduler", tick.phases.get("minecraft:nether").stage());
        }
    }
}
