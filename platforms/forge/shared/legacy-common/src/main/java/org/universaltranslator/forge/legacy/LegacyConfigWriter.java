package org.universaltranslator.forge.legacy;

import org.universaltranslator.core.DebugLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
    /** How long {@link #shutdown()} waits for the queued writes before leaving them to the daemon. */
    private static final long SHUTDOWN_WAIT_MILLIS = 2000L;

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
