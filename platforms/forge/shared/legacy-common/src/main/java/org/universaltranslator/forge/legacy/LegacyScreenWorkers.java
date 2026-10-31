package org.universaltranslator.forge.legacy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * Worker threads shared by the settings, LLM and diagnostics screens of the legacy Forge tree.
 *
 * <p>Every button press used to build a fresh {@code Thread} — a thread object, a
 * {@code ThreadLocalMap} and a megabyte of reserved stack — with a fixed name, so pressing "fetch
 * models" and "test connection" in quick succession left several same-named threads running against
 * the same endpoint. Two daemon threads are enough: the {@code fetchingModels} and {@code testing}
 * flags already serialize each kind of request, and the pool queues whatever the second button
 * starts while the first is still in flight.
 */
public final class LegacyScreenWorkers {
    /** Enough to keep "fetch models" and "test connection" from blocking each other. */
    private static final int THREADS = 2;

    private static ExecutorService workers = create();

    private LegacyScreenWorkers() {
    }

    /** Runs {@code task} on the next free shared worker. */
    public static void execute(Runnable task) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                current().execute(task);
                return;
            } catch (RejectedExecutionException shuttingDown) {
                // The pool current() handed out was closed by a shutdown before execute() reached it.
                // Running the task here would put the button's model fetch or diagnostics export on
                // the thread that pressed it, so the next pass asks current() for the replacement
                // pool instead; only a shutdown that closes that one too drops the task.
            }
        }
    }

    /**
     * Drops the pool.
     *
     * <p>Called from the client's shutdown path so a closed game does not leave the two threads (and
     * whatever request they are waiting on) behind on a daemon thread no one owns. The next
     * {@link #execute} builds a fresh pool, so a client that re-initializes still works.
     */
    public static synchronized void shutdown() {
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }

    private static synchronized ExecutorService current() {
        if (workers == null) {
            workers = create();
        }
        return workers;
    }

    private static ExecutorService create() {
        return Executors.newFixedThreadPool(THREADS, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "universal-translator-screen");
                thread.setDaemon(true);
                return thread;
            }
        });
    }
}
