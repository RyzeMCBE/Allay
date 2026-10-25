package org.allaymc.server.world;

import java.util.List;

/** Attempts every shutdown stage and releases storage while retaining the original failures. */
final class WorldShutdownSequence {
    private WorldShutdownSequence() { }

    static void run(List<Runnable> stages, Runnable save, Runnable closeStorage) {
        Throwable failure = null;
        for (var stage : stages) {
            try {
                stage.run();
            } catch (Throwable error) {
                failure = append(failure, error);
            }
        }
        if (failure == null) {
            try {
                save.run();
            } catch (Throwable error) {
                failure = error;
            }
        }
        try {
            closeStorage.run();
        } catch (Throwable error) {
            failure = append(failure, error);
        }
        if (failure != null) {
            throw new IllegalStateException("World shutdown did not complete successfully", failure);
        }
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
}
