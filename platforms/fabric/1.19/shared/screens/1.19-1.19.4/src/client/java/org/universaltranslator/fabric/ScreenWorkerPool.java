package org.universaltranslator.fabric;

import org.universaltranslator.core.DebugLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
 * <p>The threads are daemons, but the pool is still released on the way out: this class owns the only
 * reference to it, so a single {@code Runtime.addShutdownHook} registered below covers every exit
 * path, and {@link #execute} asks for a replacement pool if a button is pressed once that hook has
 * already dropped the pool.
 */
final class ScreenWorkerPool {
    /** Enough for the two network buttons to run side by side. */
    private static final int THREADS = 2;

    private static ExecutorService workers = create();
    /**
     * The diagnostics export, on a thread of its own.
     *
     * <p>Not the shared pair above: both network buttons can be in flight for as long as their
     * provider timeouts take, and an export queued behind them left its button grey — no file
     * written, nothing clickable — for that whole time.
     */
    private static ExecutorService exports;

    static {
        // Runs once, on class load. The screens that submit work have no lifecycle of their own, so
        // this is the only place a game exit can be observed from here.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }, "universal-translator-screen-worker-shutdown"));
    }

    private ScreenWorkerPool() {
    }

    /** Runs {@code task} on the next free shared worker. */
    static void execute(Runnable task) {
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
     * <p>Separate from {@link #execute} on purpose: the two network buttons can each wait out a
     * provider timeout, and the export already refused to start twice, so queueing it behind them
     * only ever served to hide that nothing had begun yet.
     */
    static void export(Runnable task) {
        try {
            currentExports().execute(task);
        } catch (RejectedExecutionException shuttingDown) {
            DebugLog.global().log("screen-work", "result", "dropped", "reason", "pool-shutdown");
        }
    }

    /**
     * Drops the pool.
     *
     * <p>The next {@link #execute} builds a fresh one, so no caller has to know whether the hook has
     * already run.
     */
    static synchronized void shutdown() {
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
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "universal-translator-screen-worker");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private static ExecutorService createExports() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "universal-translator-export");
                thread.setDaemon(true);
                return thread;
            }
        });
    }
}
