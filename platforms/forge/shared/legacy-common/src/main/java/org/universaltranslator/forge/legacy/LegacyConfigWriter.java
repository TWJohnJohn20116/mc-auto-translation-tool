package org.universaltranslator.forge.legacy;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.ShutdownBudget;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Writes the legacy configuration file off the client tick.
 *
 * <p>The F8 toggle and the Home quick toggles called {@code save()} on the tick thread: one write is
 * a temporary create, a buffered write, an atomic move and an ownership change, which an on-access
 * antivirus on Windows turns into a frozen frame. The other Forge trees moved this work to a daemon
 * writer; this is the same shape for {@code LegacyConfig}, which the shared writer cannot take
 * because it saves a different configuration class.
 */
final class LegacyConfigWriter {
    /**
     * How long {@link #shutdown()} and the Save barrier wait for the queued writes to land.
     *
     * <p>Read from {@link ShutdownBudget} so the client's stop hook, which adds this wait to the other
     * teardown steps, cannot end up shorter than the wait it is supposed to cover.
     */
    private static final long SHUTDOWN_WAIT_MILLIS = ShutdownBudget.CONFIG_WRITE_MILLIS;

    private static final Object LOCK = new Object();
    private static ExecutorService writer;

    private LegacyConfigWriter() {
    }

    /**
     * Queues one configuration write and returns immediately.
     *
     * @param config configuration to write; {@code null} does nothing
     * @param onFailure told about a failed write on the writer thread; {@code null} only logs
     */
    static void saveOffTick(final LegacyConfig config, final Consumer<Throwable> onFailure) {
        if (config == null) {
            return;
        }
        try {
            writer().execute(new Runnable() {
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
                                // A reporting callback must not kill the writer thread.
                            }
                        }
                    }
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            // The game is closing and the writer is gone: a dropped save would lose the toggle.
            writeNow(config);
        }
    }

    /**
     * Waits for the queued writes to land, then writes {@code config} on the calling thread.
     *
     * <p>The settings screens' Save has to be the last write: {@link #saveOffTick} only preserves the
     * order of the calls made on one thread, so a queued F8 toggle or Home snapshot could otherwise
     * land after the Save and put the old values back. The screens also need the exception, so that
     * a failed write can still be reported.
     *
     * @param config configuration to write; {@code null} does nothing
     */
    static void saveNow(final LegacyConfig config) throws IOException {
        if (config == null) {
            return;
        }
        drainQueuedWrites();
        config.save();
    }

    /**
     * Blocks until every write queued so far has run, or until the wait above runs out.
     *
     * <p>The barrier is an empty task: a single-thread executor runs it after everything ahead of
     * it, so returning from {@code get()} means the queue is empty. A write that failed inside its
     * own task reports through {@code onFailure}, so its exception never reaches this call. The
     * wait is bounded because this runs on the client thread and the queue can be stuck on I/O the
     * process does not control (a network or cloud-synced {@code config/} directory): freezing the
     * frame indefinitely is worse than writing in a slightly weaker order.
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
            }).get(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException timedOut) {
            DebugLog.global().log("config", "save", "drain-timeout",
                    "wait-millis", Long.toString(SHUTDOWN_WAIT_MILLIS));
        } catch (ExecutionException | RejectedExecutionException ignored) {
            // A closed writer, or a failure already reported by a queued task: the synchronous write
            // that follows is the authoritative one either way.
        }
    }

    /**
     * Flushes what is already queued and stops accepting new work.
     *
     * <p>Called when the runtime releases its configuration, which includes the game closing: the
     * queued writes get a bounded moment to land, because "press F8 and quit" must still persist.
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

    private static void writeNow(LegacyConfig config) {
        try {
            config.save();
        } catch (Throwable failure) {
            DebugLog.global().log("config", "save", "failed",
                    "reason", String.valueOf(failure.getClass().getSimpleName()));
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
