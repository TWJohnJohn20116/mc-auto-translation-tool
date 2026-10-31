package org.universaltranslator.forge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs one-shot screen work off the render thread.
 *
 * <p>The fetch and test buttons each built their own {@code Thread} per click, so a few presses left
 * several identically named threads behind (the same name in {@code jstack} and in {@code latest.log})
 * and every click paid for a fresh thread and its stack reservation. Two daemon workers are enough:
 * each action already has a flag that keeps a second one from starting while the first runs, so
 * nothing queues up here. The pool is daemon-only and is still released on the way out: this class
 * owns the only reference to it, a single {@code Runtime.addShutdownHook} registered below covers
 * every exit path, and {@link #execute} asks for a replacement pool if a button is pressed once that
 * hook has already dropped it.
 */
final class ScreenWorkers {
    /** Enough to keep "fetch models" and "test connection" from blocking each other. */
    private static final int THREADS = 2;

    private static ExecutorService workers = create();

    static {
        // Runs once, on class load. The screens that submit work have no lifecycle of their own, so
        // this is the only place a game exit can be observed from here.
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    shutdown();
                }
            }, "universal-translator-screen-worker-shutdown"));
        } catch (IllegalStateException alreadyStopping) {
            // The JVM is already shutting down. Letting this escape a static initializer would turn
            // into an ExceptionInInitializerError and leave every screen button without a worker.
        }
    }

    private ScreenWorkers() {
    }

    static void execute(Runnable task) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                current().execute(task);
                return;
            } catch (RejectedExecutionException shuttingDown) {
                // The pool current() handed out was closed by a shutdown before execute() reached it.
                // Running the task here would put the button's provider request or test connection on
                // the thread that pressed it, so the next pass asks current() for the replacement
                // pool instead; only a shutdown that closes that one too drops the task.
            }
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
    }

    private static synchronized ExecutorService current() {
        if (workers == null) {
            workers = create();
        }
        return workers;
    }

    private static ExecutorService create() {
        return Executors.newFixedThreadPool(THREADS, task -> {
            Thread thread = new Thread(task, "universal-translator-screen-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
