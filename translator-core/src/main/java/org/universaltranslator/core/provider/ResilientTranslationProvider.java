package org.universaltranslator.core.provider;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationStreamListener;
import org.universaltranslator.core.net.HttpStatusException;
import org.universaltranslator.core.net.TranslationEndpointUnavailableException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ThreadLocalRandom;

/** Adds a configurable global request interval and bounded transient-error retries. */
final class ResilientTranslationProvider implements TranslationProvider, AutoCloseable {
    /**
     * Ceiling for a server-advertised {@code Retry-After} delay. A server is free to answer
     * {@code Retry-After: 86400}, and honouring that verbatim would park the translation worker for
     * a day; 30 seconds still respects a real rate limit while keeping the request queue
     * responsive. It is deliberately far above the plain exponential backoff ceiling (2000 ms) so
     * adopting {@code Retry-After} can only ever lengthen a wait, never shorten one.
     */
    private static final long MAX_RETRY_AFTER_MILLIS = 30000L;
    /**
     * Shortest wait after a {@code 429} the server did not put a {@code Retry-After} on. The generic
     * backoff floor (200 ms) is far below any real rate-limit window, so all attempts of one window
     * landed inside it.
     */
    private static final long RATE_LIMIT_FLOOR_MILLIS = 1000L;

    private final TranslationProvider delegate;
    private final int maximumAttempts;
    private final long minimumIntervalMillis;
    /** One request per interval; the same gate {@link TencentHunyuanProvider} uses. */
    private final RequestGate gate;

    ResilientTranslationProvider(
            TranslationProvider delegate,
            int maximumAttempts,
            long minimumIntervalMillis
    ) {
        this.delegate = delegate;
        this.maximumAttempts = Math.max(1, Math.min(5, maximumAttempts));
        this.minimumIntervalMillis = Math.max(0L, Math.min(60000L, minimumIntervalMillis));
        this.gate = new RequestGate(this.minimumIntervalMillis);
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            awaitRateLimit();
            try {
                return delegate.translate(request);
            } catch (Exception exception) {
                last = exception;
                if (attempt == maximumAttempts || !isRetryable(exception)) {
                    throw exception;
                }
                long delay = retryDelayMillis(exception, attempt);
                DebugLog.global().logRetry(delegate.id(), attempt, delay, exception);
                sleepBeforeRetry(delay);
            }
        }
        throw last == null ? new IllegalStateException("Translation failed") : last;
    }

    /**
     * The streaming twin of {@link #translate(TranslationRequest)}: same interval, same bounded
     * retries, same decisions about what may be retried at all.
     *
     * <p>One case is deliberately different. Once a partial text has been shown, retrying would
     * restart the preview from a shorter string and then race the failure against it, so the attempt
     * that published something is the last one: the failure surfaces and the caller replaces the
     * preview with its error handling. Nothing has been cached at that point either way, because the
     * coordinator only stores a validated final result.
     */
    @Override
    public String translateStreaming(TranslationRequest request, TranslationStreamListener listener)
            throws Exception {
        PartialTextGuard guard = new PartialTextGuard(listener);
        Exception last = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            awaitRateLimit();
            try {
                return delegate.translateStreaming(request, guard);
            } catch (Exception exception) {
                last = exception;
                if (attempt == maximumAttempts || guard.published() || !isRetryable(exception)) {
                    throw exception;
                }
                long delay = retryDelayMillis(exception, attempt);
                DebugLog.global().logRetry(delegate.id(), attempt, delay, exception);
                sleepBeforeRetry(delay);
            }
        }
        throw last == null ? new IllegalStateException("Translation failed") : last;
    }

    /** Forwards partial texts and remembers whether any of them arrived. */
    private static final class PartialTextGuard implements TranslationStreamListener {
        private final TranslationStreamListener delegate;
        private volatile boolean published;

        private PartialTextGuard(TranslationStreamListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPartialText(String partialText) {
            published = true;
            delegate.onPartialText(partialText);
        }

        private boolean published() {
            return published;
        }
    }

    private void awaitRateLimit() throws InterruptedException {
        gate.await();
    }

    /**
     * Waits before the next attempt, keeping the cancellation visible.
     *
     * <p>{@link Thread#sleep(long)} clears the interrupt flag while it throws, so a session closed
     * during the wait looked like a normal wake-up and the loop went on to send the remaining
     * attempts to a paid endpoint. The flag is restored and the interruption rethrown instead.
     */
    private static void sleepBeforeRetry(long delay) throws InterruptedException {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled;
        }
    }

    /**
     * Delay before the next attempt: the exponential backoff, extended to the {@code Retry-After}
     * delay the server asked for when the failure carries one.
     *
     * <p>Rule: {@code wait = min(max(backoff, retryAfter), MAX_RETRY_AFTER_MILLIS)} plus a jitter of
     * up to a quarter, where {@code backoff = min(2000 ms, 200 ms << (attempt - 1))} and a
     * {@code 429} without a header raises the floor to {@link #RATE_LIMIT_FLOOR_MILLIS}. {@code max}
     * keeps the plain backoff as the floor so a small advertised value cannot make retries faster
     * than before, and the clamp bounds a hostile or misconfigured header. Because the header is
     * untrusted input, the seconds-to-milliseconds conversion is clamped before multiplying so it
     * cannot overflow.
     */
    private static long retryDelayMillis(Exception exception, int attempt) {
        long backoffMillis = Math.min(2000L, 200L << (attempt - 1));
        if (!(exception instanceof HttpStatusException)) {
            return jitter(backoffMillis);
        }
        HttpStatusException status = (HttpStatusException) exception;
        Long retryAfterSeconds = status.getRetryAfterSeconds();
        if (retryAfterSeconds == null) {
            // A 429 without Retry-After used to wait the plain 200 ms, so one rate-limit window was
            // hit by every one of the attempts in a row. A rate limit is the one answer that says
            // "come back later", so it gets a floor an order of magnitude above a generic retry.
            long floor = status.getStatusCode() == 429 ? RATE_LIMIT_FLOOR_MILLIS : backoffMillis;
            return jitter(Math.max(backoffMillis, floor));
        }
        long seconds = retryAfterSeconds.longValue();
        long retryAfterMillis = seconds >= MAX_RETRY_AFTER_MILLIS / 1000L
                ? MAX_RETRY_AFTER_MILLIS
                : seconds * 1000L;
        return jitter(Math.min(MAX_RETRY_AFTER_MILLIS, Math.max(backoffMillis, retryAfterMillis)));
    }

    /**
     * Spreads the wait so the workers do not retry in lockstep.
     *
     * <p>Every worker sees the same 429 or 5xx at the same moment, and an identical delay made them
     * all hammer the endpoint again on the same millisecond. The jitter is added, never subtracted,
     * so it cannot shorten a wait below the backoff or below a server-requested {@code Retry-After}.
     */
    private static long jitter(long delayMillis) {
        if (delayMillis <= 0L) {
            return delayMillis;
        }
        long spread = Math.max(1L, delayMillis / 4L);
        return delayMillis + ThreadLocalRandom.current().nextLong(spread);
    }

    private static boolean isRetryable(Exception exception) {
        if (exception instanceof TranslationEndpointUnavailableException) {
            return false;
        }
        if (exception instanceof HttpStatusException) {
            return ((HttpStatusException) exception).isRetryable();
        }
        // A read or connect timeout is a network failure worth another attempt, and it is also an
        // InterruptedIOException, so it has to be recognised before the cancellation test below.
        if (exception instanceof SocketTimeoutException) {
            return true;
        }
        // A cancelled session surfaces as an InterruptedIOException. Retrying it sent the remaining
        // attempts after the worker had already been asked to stop.
        if (exception instanceof InterruptedIOException) {
            return false;
        }
        return exception instanceof IOException;
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable) {
            ((AutoCloseable) delegate).close();
        }
    }
}
