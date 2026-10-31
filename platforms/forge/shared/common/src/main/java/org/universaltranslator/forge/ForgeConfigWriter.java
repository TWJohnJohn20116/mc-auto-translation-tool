package org.universaltranslator.forge;

import org.universaltranslator.core.DebugLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
                            // A reporting callback must not kill the writer thread; the failure is
                            // already recorded in the debug log above.
                        }
                    }
                }
            }
        });
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
