package org.universaltranslator.fabric;

import org.universaltranslator.core.DebugLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * Worker threads shared by the settings, LLM and diagnostics screens of this tree.
 *
 * <p>Every button press used to build a fresh {@code Thread} — a thread object, a
 * {@code ThreadLocalMap} and a megabyte of reserved stack — with a fixed name, so pressing "fetch
 * models" and "test connection" in quick succession left several same-named threads running against
 * the same endpoint and made a thread dump guesswork. Two daemon threads are enough: the
 * {@code fetching} and the export's own pending flag already serialize each kind of request, and the
 * pool queues whatever the second button starts while the first is still in flight.
 */
public final class ScreenWorkers {
    /** Enough to keep "fetch models" and "export diagnostics" from blocking each other. */
    private static final int THREADS = 2;

    private static ExecutorService workers = create();
    /**
     * The diagnostics export, on a thread of its own.
     *
     * <p>Not the shared pair above: the model fetch can be in flight for as long as its provider
     * timeout takes, and an export queued behind it left its button flat — no file written, nothing
     * clickable — for that whole time.
     */
    private static ExecutorService exports;

    private ScreenWorkers() {
    }

    /** Runs {@code task} on the next free shared worker. */
    public static void execute(Runnable task) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                current().execute(task);
                return;
            } catch (RejectedExecutionException shuttingDown) {
                // The pool current() handed out was closed by a shutdown before execute() reached it.
                // Running the task here would put the button's provider request or diagnostics export
                // on the thread that pressed it, so the next pass asks current() for the replacement
                // pool instead; only a shutdown that closes that one too drops the task.
            }
        }
        // Reachable only when a shutdown closed the replacement pool as well: the button's work is
        // dropped rather than run on the render thread, and this record says why it left no output.
        DebugLog.global().log("screen-work", "result", "dropped", "reason", "pool-shutdown");
    }

    /**
     * Runs one diagnostics export on the export thread instead of the shared request workers.
     *
     * <p>Separate from {@link #execute} on purpose: the pool above is sized for the network buttons,
     * whose requests can each wait out a provider timeout, so an export queued behind them sat
     * invisible until they had finished.
     */
    public static void export(Runnable task) {
        try {
            currentExports().execute(task);
        } catch (RejectedExecutionException shuttingDown) {
            DebugLog.global().log("screen-work", "result", "dropped", "reason", "pool-shutdown");
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
        if (exports != null) {
            exports.shutdownNow();
            exports = null;
        }
    }

    private static synchronized ExecutorService current() {
        if (workers == null) {
            workers = create();
        }
        return workers;
    }

    private static synchronized ExecutorService currentExports() {
        if (exports == null) {
            exports = createExports();
        }
        return exports;
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

    private static ExecutorService createExports() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "universal-translator-export");
                thread.setDaemon(true);
                return thread;
            }
        });
    }
}
