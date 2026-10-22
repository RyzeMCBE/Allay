package org.allaymc.server.world;

import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in observation of parallel dimension ticks for issue #27.
 *
 * <p>The watchdog is deliberately independent of the world tick and compute
 * pool. It never cancels, interrupts, retries or otherwise modifies tick tasks,
 * and produces at most one stack snapshot per slow tick.</p>
 */
@Slf4j
final class ParallelDimensionTickDiagnostics implements AutoCloseable {
    static final long STALL_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(8);
    private static final int MAX_REPORTED_THREADS = 24;
    private static final int MAX_STACK_FRAMES = 48;

    private final String worldName;
    private final Thread worldThread;
    private final ExecutorService computePool;
    private volatile Tick activeTick;
    private volatile boolean running;
    private Thread watchdog;

    ParallelDimensionTickDiagnostics(String worldName, Thread worldThread, ExecutorService computePool) {
        this.worldName = worldName;
        this.worldThread = worldThread;
        this.computePool = computePool;
    }

    Tick beginTick(long tickNumber, long nowNanos) {
        var tick = new Tick(tickNumber, nowNanos);
        activeTick = tick;
        return tick;
    }

    void dimensionPhase(String dimension, String phase) {
        var tick = activeTick;
        if (tick != null) {
            tick.phases.put(dimension, new Phase(Thread.currentThread(), phase));
        }
    }

    void completeTick(Tick tick) {
        if (activeTick == tick) {
            activeTick = null;
        }
    }

    Tick claimStalledTick(long nowNanos) {
        var tick = activeTick;
        if (tick == null || nowNanos - tick.startedNanos < STALL_THRESHOLD_NANOS) {
            return null;
        }
        return tick.reported.compareAndSet(false, true) ? tick : null;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        // A platform daemon can report a ForkJoinPool stall even if all
        // compute workers are blocked. No virtual-thread executor is added.
        watchdog = Thread.ofPlatform()
                .daemon(true)
                .name("Parallel Tick Diagnostic #" + worldName)
                .start(this::monitor);
    }

    private void monitor() {
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                if (!running) {
                    return;
                }
                continue;
            }
            var tick = claimStalledTick(System.nanoTime());
            if (tick != null) {
                try {
                    report(tick);
                } catch (Throwable failure) {
                    log.error("Could not capture parallel tick diagnostics for world {}", worldName, failure);
                }
            }
        }
    }

    private void report(Tick tick) {
        var phases = tick.phases.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue().stage() + "@" + e.getValue().thread().getName())
                .toList();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tick.startedNanos);
        log.error("PARALLEL_DIMENSION_STALL_SUSPECTED world={} tick={} elapsedMs={} " +
                        "dimensionStages={} computePool={}",
                worldName, tick.tickNumber, elapsedMs, phases, poolSummary());

        // The JVM's deadlock detector does not detect every pool starvation
        // scenario. A null result must not be interpreted as proof of safety.
        var bean = ManagementFactory.getThreadMXBean();
        log.error("PARALLEL_DIMENSION_STALL_SUSPECTED world={} jvmDeadlockedThreadIds={}",
                worldName, java.util.Arrays.toString(bean.findDeadlockedThreads()));

        var relevant = new LinkedHashSet<Thread>();
        relevant.add(worldThread);
        tick.phases.values().forEach(p -> relevant.add(p.thread()));
        // Include compute workers that may be running physics tasks that have
        // not yet been recorded as a dimension's outer task.
        Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("AllayComputeThread-"))
                .sorted(Comparator.comparing(Thread::getName))
                .limit(MAX_REPORTED_THREADS)
                .forEach(relevant::add);

        int printed = 0;
        for (var thread : relevant) {
            if (printed++ >= MAX_REPORTED_THREADS) {
                break;
            }
            var stack = thread.getStackTrace();
            var trace = new ArrayList<String>(Math.min(stack.length, MAX_STACK_FRAMES));
            for (int i = 0; i < stack.length && i < MAX_STACK_FRAMES; i++) {
                trace.add("  at " + stack[i]);
            }
            log.error("PARALLEL_DIMENSION_STALL_SUSPECTED thread={} id={} state={} stack:\n{}",
                    thread.getName(), thread.threadId(), thread.getState(), String.join("\n", trace));
        }
    }

    private String poolSummary() {
        if (computePool instanceof ForkJoinPool pool) {
            return "parallelism=" + pool.getParallelism() +
                    ",size=" + pool.getPoolSize() +
                    ",active=" + pool.getActiveThreadCount() +
                    ",queuedTasks=" + pool.getQueuedTaskCount() +
                    ",queuedSubmissions=" + pool.getQueuedSubmissionCount();
        }
        return computePool.getClass().getSimpleName();
    }

    @Override
    public void close() {
        running = false;
        var thread = watchdog;
        if (thread != null) {
            thread.interrupt();
        }
    }

    static final class Tick {
        final long tickNumber;
        final long startedNanos;
        final ConcurrentHashMap<String, Phase> phases = new ConcurrentHashMap<>();
        final AtomicBoolean reported = new AtomicBoolean();

        Tick(long tickNumber, long startedNanos) {
            this.tickNumber = tickNumber;
            this.startedNanos = startedNanos;
        }
    }

    record Phase(Thread thread, String stage) {
    }
}
