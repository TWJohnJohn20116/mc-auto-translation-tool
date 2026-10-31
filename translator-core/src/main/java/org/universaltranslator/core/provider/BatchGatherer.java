package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.HttpStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Groups texts that arrive close together into one request, a bounded number of groups per job.
 *
 * <p>This used to be a single global {@code boolean batchServing} flag inside
 * {@link OpenAiChatTranslationProvider}: while any leader was in its HTTP round trip, every other
 * worker waited, whatever language or text kind it carried. The provider's real concurrency was
 * therefore one request at a time, and a hung endpoint turned one failure into
 * {@code workers × api-read-timeout} — while the waiters had no deadline at all and only slept in
 * one-second slices.
 *
 * <p>Four things changed:
 *
 * <ul>
 *   <li>the single-flight role is per job — target language, text kind and source language — so a
 *       second language or a second kind no longer queues behind an unrelated request;
 *   <li>one job may have several groups in flight, as many as there are texts waiting for a group of
 *       their own, so {@code translation-workers} buys parallelism instead of one leader per session;
 *   <li>a waiter has a total deadline. When it expires and nobody has claimed the text yet, that
 *       caller sends its own request instead of parking a worker behind a dead leader; once a leader
 *       has taken the text the waiter keeps waiting, because a request of its own would pay for the
 *       same text twice;
 *   <li>a waiter sleeps until a leader announces itself or its own deadline expires, so shutdown is
 *       noticed through the interrupt rather than after a full second — and a waiter no longer
 *       re-takes the collection lock 900 times per request.
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
        /** Set by the leader that put this entry into its group, under the same lock. */
        private boolean claimed;

        private Entry(TranslationRequest request) {
            this.request = request;
        }
    }

    /**
     * How long a waiter accepts before it stops waiting for a leader. It is above the default read
     * timeout so a slow-but-answering endpoint still batches, and finite so a leader that never
     * answers cannot retire the worker.
     */
    private static final long WAITER_TIMEOUT_MILLIS = 180000L;
    /**
     * Base delay before the one whole-group retry.
     *
     * <p>It is {@link ResilientTranslationProvider}'s own first-attempt backoff, so one reissued
     * batch waits exactly what one retried single request waits; the rate-limit floor and the
     * {@code Retry-After} header raise it from there.
     */
    private static final long GROUP_RETRY_BACKOFF_MILLIS = 200L;

    private final Object lock = new Object();
    private final List<Entry> pending = new ArrayList<Entry>();
    /** Leaders in flight per job key. */
    private final Map<String, Integer> serving = new HashMap<String, Integer>();
    private final int batchSize;
    /** How many groups of one job may be in request at the same time. */
    private final int maximumLeadersPerJob;
    /**
     * The same pacing {@link ResilientTranslationProvider} applies, for the requests this class
     * sends on its own.
     *
     * <p>The resilient layer awaits its gate once per translation call, and a batch sends more than
     * one request from inside that call: the whole-group retry, and one repair request per line the
     * reply did not answer. Those bypassed {@code api-min-request-interval-ms} entirely, so the most
     * expensive request there is — eight lines and a large completion budget — went out twice within
     * a few hundred milliseconds. One extra gate can only ever be more conservative than the outer
     * one; the interval is the number the user already set.
     */
    private final RequestGate gate;
    private long windowMillis;

    BatchGatherer(int batchSize, long windowMillis) {
        this(batchSize, windowMillis, 1, TranslationTuning.DEFAULT_REQUEST_INTERVAL_MILLIS);
    }

    BatchGatherer(int batchSize, long windowMillis, int maximumLeadersPerJob) {
        this(batchSize, windowMillis, maximumLeadersPerJob,
                TranslationTuning.DEFAULT_REQUEST_INTERVAL_MILLIS);
    }

    /**
     * @param batchSize largest number of texts one request may carry
     * @param windowMillis how long a leader waits for siblings
     * @param maximumLeadersPerJob how many groups of one job may be in request at the same time; 1 is
     *                            the historical one-leader-per-job behaviour
     * @param requestIntervalMillis smallest spacing between two requests this gatherer sends, in
     *                              milliseconds; 0 sends them as fast as the workers allow
     */
    BatchGatherer(int batchSize, long windowMillis, int maximumLeadersPerJob,
            int requestIntervalMillis) {
        this.batchSize = Math.max(1, batchSize);
        this.windowMillis = Math.max(0L, windowMillis);
        this.maximumLeadersPerJob = Math.max(1, maximumLeadersPerJob);
        this.gate = new RequestGate(requestIntervalMillis);
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
        // The request never changes, so its job key is built once instead of once per wake-up — and
        // never inside the collection lock.
        String key = jobKey(request);
        synchronized (lock) {
            pending.add(entry);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAITER_TIMEOUT_MILLIS);
        while (true) {
            boolean lead = false;
            boolean abandoned = false;
            synchronized (lock) {
                if (entry.settled) {
                    break;
                }
                if (mayLead(key, entry)) {
                    serving.put(key, Integer.valueOf(leaderCount(key) + 1));
                    lead = true;
                } else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        if (!entry.claimed && pending.remove(entry)) {
                            // Nobody claimed it, so this caller owns its own translation now. Better
                            // one request of our own than a worker parked behind a leader that never
                            // comes back.
                            abandoned = true;
                        } else {
                            // A leader already carries this text, so a request of our own would pay
                            // for it a second time; the same holds for an entry that is between the
                            // queue and the group. Give this waiter another window instead.
                            deadline = System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(WAITER_TIMEOUT_MILLIS);
                            remaining = deadline - System.nanoTime();
                        }
                    }
                    if (!abandoned) {
                        try {
                            // One wait until a leader announces itself or this waiter's own window
                            // expires. Slicing it here re-took the collection lock — the same lock
                            // the leader collects under — hundreds of times per request.
                            lock.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
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

    /**
     * Whether this caller may send a group request of its own for {@code key}.
     *
     * <p>One leader is always allowed. A second one is only worth a request of its own when more
     * than one group's worth of texts is waiting, and never more leaders than there are rows to
     * carry: otherwise every worker starts a group whose texts the first leader would have taken.
     */
    private boolean mayLead(String key, Entry entry) {
        if (!pending.contains(entry)) {
            return false;
        }
        int leaders = leaderCount(key);
        if (leaders == 0) {
            return true;
        }
        int waiting = 0;
        for (Entry candidate : pending) {
            if (sameJob(candidate.request, entry.request)) {
                waiting++;
            }
        }
        int wanted = (waiting + batchSize - 1) / batchSize;
        return leaders < Math.min(maximumLeadersPerJob, wanted);
    }

    private int leaderCount(String key) {
        Integer count = serving.get(key);
        return count == null ? 0 : count.intValue();
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
                    leader.claimed = true;
                    group.add(leader);
                }
                Iterator<Entry> iterator = pending.iterator();
                while (iterator.hasNext() && group.size() < batchSize) {
                    Entry candidate = iterator.next();
                    if (sameJob(candidate.request, leader.request)) {
                        iterator.remove();
                        candidate.claimed = true;
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
                int remaining = leaderCount(key) - 1;
                if (remaining <= 0) {
                    serving.remove(key);
                } else {
                    serving.put(key, Integer.valueOf(remaining));
                }
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
        // Paced like any other request to this endpoint: the outer gate is claimed once per
        // translation call, and a batch sends several requests from inside one. The wait is outside
        // the try below, because an interruption here is a cancellation, not a batch failure.
        gate.await();
        try {
            translated = work.translateGroup(requests);
        } catch (Exception batchFailure) {
            if (!FailurePolicy.repairablePerLine(batchFailure)) {
                // An authentication, quota, transport or cancellation answer is the same for every
                // line: repeating it seven more times only multiplies the failure. One retry of the
                // whole group rides out a transient 5xx for one request instead of one per line — but
                // it waits first: reissued a few milliseconds after a 429 it landed inside the same
                // rate-limit window, which is exactly the pattern an endpoint reads as scanning.
                if (FailurePolicy.retryableAsGroup(batchFailure)) {
                    awaitGroupRetry(batchFailure);
                    // The reissued request is a request like any other, so the interval the user
                    // configured applies to it as well rather than only the backoff above.
                    gate.await();
                    translated = work.translateGroup(requests);
                } else {
                    throw batchFailure;
                }
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
            if (Thread.currentThread().isInterrupted()) {
                // A closed session must not keep sending one paid request per remaining line.
                throw new InterruptedException("A batched translation was cancelled");
            }
            // Per-line repair: a reply that answered seven of eight lines costs one extra request
            // for the eighth, instead of resending all eight. It is a request of its own, so it is
            // paced like one — otherwise one failed batch produced eight unpaced requests.
            gate.await();
            translateAlone(entry, work);
        }
    }

    /**
     * Waits before the one whole-group retry.
     *
     * <p>{@link ResilientTranslationProvider} already knows how long to wait — it honours
     * {@code Retry-After}, floors a bare {@code 429} at
     * {@link ResilientTranslationProvider#RATE_LIMIT_FLOOR_MILLIS} and adds jitter — but it wraps
     * this gatherer from the outside, so a retry issued here bypassed all of it.
     *
     * @param failure what the group request threw
     * @throws InterruptedException when the session was closed during the wait
     */
    private static void awaitGroupRetry(Exception failure) throws InterruptedException {
        try {
            Thread.sleep(groupRetryDelayMillis(failure));
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled;
        }
    }

    /** Delay before one group retry: the backoff raised to a rate limit's floor or its Retry-After. */
    private static long groupRetryDelayMillis(Exception failure) {
        long backoff = GROUP_RETRY_BACKOFF_MILLIS;
        HttpStatusException status = statusIn(failure);
        if (status == null) {
            return jitter(backoff);
        }
        Long retryAfterSeconds = status.getRetryAfterSeconds();
        if (retryAfterSeconds == null) {
            long floor = status.getStatusCode() == 429
                    ? ResilientTranslationProvider.RATE_LIMIT_FLOOR_MILLIS : backoff;
            return jitter(Math.max(backoff, floor));
        }
        long seconds = retryAfterSeconds.longValue();
        long retryAfterMillis = seconds >= ResilientTranslationProvider.MAX_RETRY_AFTER_MILLIS / 1000L
                ? ResilientTranslationProvider.MAX_RETRY_AFTER_MILLIS
                : seconds * 1000L;
        return jitter(Math.min(ResilientTranslationProvider.MAX_RETRY_AFTER_MILLIS,
                Math.max(backoff, retryAfterMillis)));
    }

    /** The HTTP status inside one cause chain, or {@code null} when there is none. */
    private static HttpStatusException statusIn(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < FailurePolicy.MAXIMUM_CAUSE_DEPTH; depth++) {
            if (current instanceof HttpStatusException) {
                return (HttpStatusException) current;
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return null;
    }

    /** Spreads the wait so the workers do not retry in lockstep; added, never subtracted. */
    private static long jitter(long delayMillis) {
        long spread = Math.max(1L, delayMillis / 4L);
        return delayMillis + ThreadLocalRandom.current().nextLong(spread);
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
