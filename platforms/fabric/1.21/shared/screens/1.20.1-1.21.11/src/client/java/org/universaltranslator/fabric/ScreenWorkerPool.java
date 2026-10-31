package org.universaltranslator.fabric;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Runs one-shot screen work off the render thread.
 *
 * <p>The model-fetch, connection-test and diagnostics-export buttons each built their own
 * {@code Thread} per click: a new thread object, a new {@code ThreadLocalMap} and a reserved stack
 * for every press, all under the same fixed name, so holding a button down left a row of identical
 * threads in {@code jstack} and in the log. Two daemon workers are enough — every one of those
 * actions already has a flag that refuses a second run while the first is in flight.
 *
 * <p>The threads are daemons and hold no per-screen state, so there is nothing to release when a
 * screen closes or the player quits; the pool dies with the JVM.
 */
final class ScreenWorkerPool {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "universal-translator-screen-worker");
            thread.setDaemon(true);
            return thread;
        }
    });

    private ScreenWorkerPool() {
    }

    static void execute(Runnable task) {
        WORKERS.execute(task);
    }
}
