package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Groups texts that arrive close together into one request, one group per job at a time.
 *
 * <p>This used to be a single global {@code boolean batchServing} flag inside
 * {@link OpenAiChatTranslationProvider}: while any leader was in its HTTP round trip, every other
 * worker waited, whatever language or text kind it carried. The provider's real concurrency was
 * therefore one request at a time, and a hung endpoint turned one failure into
 * {@code workers × api-read-timeout} — while the waiters had no deadline at all and only slept in
 * one-second slices.
 *
 * <p>Three things changed:
 *
 * <ul>
 *   <li>the single-flight role is per job — target language, text kind and source language — so a
 *       second language or a second kind no longer queues behind an unrelated request;
 *   <li>a waiter has a total deadline. When it expires and nobody has claimed the text yet, that
 *       caller sends its own request instead of parking a worker behind a dead leader;
 *   <li>the wait slice is short, so shutdown notices promptly instead of after a full second.
 * </ul>
 *
 * <p>The queue only ever holds requests, never results: one group is served by the thread that
 * elected itself leader, and every entry is settled exactly once in its {@code finally} block.
 */
final class BatchGatherer {
    /** What one group is translated with; implemented by the provider that owns the protocol. */
    interface Work {
        /** Translates exactly one text with a request of its own. */
        String translateAlone(TranslationRequest request) throws Exception;

        /**
         * Translates a group in one request.
         *
         * @return one translation per request in order, with {@code null} where the reply answered
         *         nothing for that line
         */
        List<String> translateGroup(List<TranslationRequest> requests) throws Exception;
    }

    /** One pending text, waiting for the request that will translate it. */
    private static final class Entry {
        private final TranslationRequest request;
        private String translated;
        private Exception failure;
        private boolean settled;

        private Entry(TranslationRequest request) {
            this.request = request;
        }
    }

    /** Granularity of the wait, so shutdown is noticed without a one-second delay. */
    private static final long WAIT_SLICE_MILLIS = 200L;
    /**
     * How long a waiter accepts before it stops waiting for a leader. It is above the default read
     * timeout so a slow-but-answering endpoint still batches, and finite so a leader that never
     * answers cannot retire the worker.
     */
    private static final long WAITER_TIMEOUT_MILLIS = 180000L;

    private final Object lock = new Object();
    private final List<Entry> pending = new ArrayList<Entry>();
    /** Job keys that currently have a leader. */
    private final Set<String> serving = new HashSet<String>();
    private final int batchSize;
    private long windowMillis;

    BatchGatherer(int batchSize, long windowMillis) {
        this.batchSize = Math.max(1, batchSize);
        this.windowMillis = Math.max(0L, windowMillis);
    }

    /** Widens or narrows the collection window; the test seam and the configuration both use it. */
    void setWindowMillis(long millis) {
        synchronized (lock) {
            this.windowMillis = Math.max(0L, millis);
        }
    }

    /** Whether one request may carry more than one text. */
    boolean batching() {
        return batchSize > 1;
    }

    /** Number of texts one request may carry. */
    int batchSize() {
        return batchSize;
    }

    /**
     * Queues one text and returns its translation, either from the group request or from a request
     * of its own.
     *
     * @param request text to translate
     * @param work protocol implementation
     * @return the translation
     * @throws Exception whatever the request failed with
     */
    String translate(TranslationRequest request, Work work) throws Exception {
        Entry entry = new Entry(request);
        synchronized (lock) {
            pending.add(entry);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAITER_TIMEOUT_MILLIS);
        long hardDeadline = deadline + TimeUnit.MILLISECONDS.toNanos(WAITER_TIMEOUT_MILLIS);
        while (true) {
            boolean lead = false;
            boolean abandoned = false;
            synchronized (lock) {
                if (entry.settled) {
                    break;
                }
                String key = jobKey(entry.request);
                if (!serving.contains(key) && pending.contains(entry)) {
                    serving.add(key);
                    lead = true;
                } else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L && pending.remove(entry)) {
                        // Nobody claimed it, so this caller owns its own translation now. Better one
                        // request of our own than a worker parked behind a leader that never comes
                        // back.
                        abandoned = true;
                    } else if (remaining <= 0L && hardDeadline - System.nanoTime() <= 0L) {
                        throw new IOException(
                                "A batched translation request did not answer in time");
                    } else {
                        long slice = remaining > 0L
                                ? Math.min(WAIT_SLICE_MILLIS,
                                        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
                                : WAIT_SLICE_MILLIS;
                        try {
                            lock.wait(slice);
                        } catch (InterruptedException interrupted) {
                            // Left in the queue, this entry would be served by a batch request nobody
                            // is waiting for, spending a paid call on a text whose caller is gone.
                            pending.remove(entry);
                            entry.failure = interrupted;
                            entry.settled = true;
                            Thread.currentThread().interrupt();
                            throw interrupted;
                        }
                        continue;
                    }
                }
            }
            if (abandoned) {
                return work.translateAlone(entry.request);
            }
            if (lead) {
                serve(entry, work);
            }
        }
        if (entry.failure != null) {
            throw entry.failure;
        }
        return entry.translated;
    }

    /** Serves one group of compatible texts, then releases the leader role for that job. */
    private void serve(Entry leader, Work work) {
        String key = jobKey(leader.request);
        List<Entry> group = new ArrayList<Entry>(batchSize);
        try {
            long window;
            synchronized (lock) {
                window = windowMillis;
            }
            if (window > 0L) {
                Thread.sleep(window);
            }
            synchronized (lock) {
                // The leader goes first: it must never be left in the queue because earlier entries
                // filled the group, which would re-elect it for the same wait forever.
                if (pending.remove(leader)) {
                    group.add(leader);
                }
                Iterator<Entry> iterator = pending.iterator();
                while (iterator.hasNext() && group.size() < batchSize) {
                    Entry candidate = iterator.next();
                    if (sameJob(candidate.request, leader.request)) {
                        iterator.remove();
                        group.add(candidate);
                    }
                }
            }
            if (!group.isEmpty()) {
                translateGroup(group, work);
            }
        } catch (InterruptedException interrupted) {
            // Leaving the leader queued with the interrupt flag set was an endless spin: the flag
            // made the collection window's sleep throw immediately, this handler set the flag again,
            // and the loop above re-elected the same entry every time. Dropping it is what lets the
            // loop break.
            synchronized (lock) {
                pending.remove(leader);
                leader.settled = true;
            }
            leader.failure = interrupted;
            Thread.currentThread().interrupt();
            failGroup(group, interrupted);
        } catch (Exception unexpected) {
            failGroup(group, unexpected);
        } finally {
            synchronized (lock) {
                for (Entry entry : group) {
                    entry.settled = true;
                }
                serving.remove(key);
                lock.notifyAll();
            }
        }
    }

    private void translateGroup(List<Entry> group, Work work) throws Exception {
        if (group.size() == 1) {
            translateAlone(group.get(0), work);
            return;
        }
        List<TranslationRequest> requests = new ArrayList<TranslationRequest>(group.size());
        for (Entry entry : group) {
            requests.add(entry.request);
        }
        List<String> translated = null;
        try {
            translated = work.translateGroup(requests);
        } catch (Exception batchFailure) {
            if (!FailurePolicy.repairablePerLine(batchFailure)) {
                // An authentication, quota or "model not found" answer is the same for every line:
                // repeating it seven more times only multiplies the failure.
                throw batchFailure;
            }
            // A reply that answered only some of the lines arrives here as holes instead, and only
            // those lines are repaired below.
        }
        for (int index = 0; index < group.size(); index++) {
            Entry entry = group.get(index);
            String value = translated == null ? null : translated.get(index);
            if (value != null) {
                entry.translated = value;
                continue;
            }
            // Per-line repair: a reply that answered seven of eight lines costs one extra request
            // for the eighth, instead of resending all eight.
            translateAlone(entry, work);
        }
    }

    /** Translates one entry with a request of its own, recording whatever it produces. */
    private static void translateAlone(Entry entry, Work work) {
        try {
            entry.translated = work.translateAlone(entry.request);
        } catch (Exception failure) {
            entry.failure = failure;
        }
    }

    private static void failGroup(List<Entry> group, Exception failure) {
        for (Entry entry : group) {
            if (entry.translated == null && entry.failure == null) {
                entry.failure = failure;
            }
        }
    }

    /** Whether two requests share one prompt, and therefore may share one batch. */
    static boolean sameJob(TranslationRequest left, TranslationRequest right) {
        return left.getTargetLanguage().equals(right.getTargetLanguage())
                && left.getKind() == right.getKind()
                && String.valueOf(left.getSourceLanguage())
                        .equals(String.valueOf(right.getSourceLanguage()));
    }

    /** Stable key of the job one request belongs to. */
    private static String jobKey(TranslationRequest request) {
        return request.getTargetLanguage() + '\u0000' + request.getKind().name() + '\u0000'
                + String.valueOf(request.getSourceLanguage());
    }
}
