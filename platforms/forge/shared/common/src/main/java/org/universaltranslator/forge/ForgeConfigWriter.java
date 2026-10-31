package org.universaltranslator.forge;

import org.universaltranslator.core.DebugLog;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Writes configuration files off the client thread.
 *
 * <p>One Save is a temporary-file create, a buffered write, an atomic move and a second ownership
 * change; on Windows an on-access antivirus turns those four steps into tens of milliseconds. Both
 * callers that reach {@code save()} while the game runs — the F8 toggle and the settings screen's
 * Save — did it on the thread that is about to draw the next frame, so a save showed up as a stalled
 * frame. Writing from one daemon thread keeps the order the calls were made in and never blocks the
 * caller.
 */
final class ForgeConfigWriter {
    /** How long {@link #shutdown()} waits for the queued writes before leaving them to the daemon. */
    private static final long SHUTDOWN_WAIT_MILLIS = 2000L;

    private static final Object LOCK = new Object();
    private static ExecutorService writer;

    private ForgeConfigWriter() {
    }

    /**
     * Queues one configuration write and returns immediately.
     *
     * @param config configuration to write; {@code null} does nothing
     * @param onFailure told about a failed write on the writer thread; {@code null} only logs
     */
    static void saveOffTick(final ForgeConfig config, final Consumer<Throwable> onFailure) {
        if (config == null) {
            return;
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    config.save();
                } catch (Throwable failure) {
                    DebugLog.global().log("config", "save", "failed",
                            "reason", String.valueOf(failure.getClass().getSimpleName()));
                    if (onFailure != null) {
                        try {
                            onFailure.accept(failure);
                        } catch (RuntimeException ignored) {
                            // A reporting callback must not kill the writer thread; the failure is
                            // already recorded in the debug log above.
                        }
                    }
                }
            }
        };
        try {
            writer().execute(task);
        } catch (RejectedExecutionException shuttingDown) {
            // The executor is only ever shut down while the game closes, and the check in writer()
            // cannot hold a lock across execute(): a write rejected here would silently lose the
            // toggle, so it is performed on the caller instead.
            task.run();
        }
    }

    /**
     * Waits for the queued writes to land, then writes {@code config} on the calling thread.
     *
     * <p>The settings screen's Save has to be the last write: {@link #saveOffTick} only preserves the
     * order of the calls made on one thread, so a queued F8 toggle or Home snapshot could otherwise
     * land after the Save and put the old values back. The screen also needs a synchronous result to
     * report a failure through {@code status.universal_translator.save_failed}.
     *
     * @param config configuration to write; {@code null} does nothing
     */
    static void saveNow(final ForgeConfig config) throws IOException {
        if (config == null) {
            return;
        }
        drainQueuedWrites();
        config.save();
    }

    /**
     * Blocks until every write queued so far has run.
     *
     * <p>The barrier is an empty task: a single-thread executor runs it after everything ahead of
     * it, so returning from {@code get()} means the queue is empty. A write that failed inside its
     * own task reports through {@code onFailure}, so its exception never reaches this call.
     */
    private static void drainQueuedWrites() {
        ExecutorService current;
        synchronized (LOCK) {
            current = writer;
        }
        if (current == null) {
            return;
        }
        try {
            current.submit(new Runnable() {
                @Override
                public void run() {
                    // Nothing to do: reaching the end of the queue is the whole point.
                }
            }).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RejectedExecutionException ignored) {
            // A closed writer, or a failure already reported by a queued task: the synchronous write
            // that follows is the authoritative one either way.
        }
    }

    /**
     * Flushes what is already queued and stops accepting new work.
     *
     * <p>Called when the client drops its configuration, which includes the case where the game is
     * closing: the queued writes are given a bounded moment to land, because "press F8 and quit"
     * must still persist the toggle. The executor is recreated on the next {@link #saveOffTick}.
     */
    static void shutdown() {
        ExecutorService current;
        synchronized (LOCK) {
            current = writer;
            writer = null;
        }
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService writer() {
        synchronized (LOCK) {
            if (writer == null || writer.isShutdown()) {
                writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable task) {
                        Thread thread = new Thread(task, "universal-translator-config-writer");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
            }
            return writer;
        }
    }
}
