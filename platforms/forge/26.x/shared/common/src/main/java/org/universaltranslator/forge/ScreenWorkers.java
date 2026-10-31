package org.universaltranslator.forge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs one-shot screen work off the render thread.
 *
 * <p>The fetch and test buttons each built their own {@code Thread} per click, so a few presses left
 * several identically named threads behind (the same name in {@code jstack} and in {@code latest.log})
 * and every click paid for a fresh thread and its stack reservation. Two daemon workers are enough:
 * each action already has a flag that keeps a second one from starting while the first runs, so
 * nothing queues up here. The pool is daemon-only and never restarted, so it needs no shutdown hook;
 * the process ends with it.
 */
final class ScreenWorkers {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "universal-translator-screen-worker");
        thread.setDaemon(true);
        return thread;
    });

    private ScreenWorkers() {
    }

    static void execute(Runnable task) {
        WORKERS.execute(task);
    }
}
