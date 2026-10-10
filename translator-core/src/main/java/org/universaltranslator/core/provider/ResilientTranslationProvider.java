package org.universaltranslator.core.provider;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationStreamListener;
import org.universaltranslator.core.net.HttpStatusException;
import org.universaltranslator.core.net.TranslationEndpointUnavailableException;

import java.io.IOException;

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

    private final TranslationProvider delegate;
    private final int maximumAttempts;
    private final long minimumIntervalMillis;
    private long nextRequestAtMillis;

    ResilientTranslationProvider(
            TranslationProvider delegate,
            int maximumAttempts,
            long minimumIntervalMillis
    ) {
        this.delegate = delegate;
        this.maximumAttempts = Math.max(1, Math.min(5, maximumAttempts));
        this.minimumIntervalMillis = Math.max(0L, Math.min(60000L, minimumIntervalMillis));
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
                Thread.sleep(delay);
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
                Thread.sleep(delay);
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
        while (true) {
            long wait;
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (nextRequestAtMillis <= now) {
                    // Claim this instant and move the next slot one interval ahead. Claiming is
                    // atomic, so two callers can never pass the gate for the same slot.
                    nextRequestAtMillis = now + minimumIntervalMillis;
                    return;
                }
                wait = nextRequestAtMillis - now;
            }
            // Sleep outside the lock: a caller that has to wait must not block the other callers
            // from reserving their own (later) slots. Re-check on wake-up so a late wake still
            // pushes the following slot, exactly like the previous lock-held implementation.
            Thread.sleep(wait);
        }
    }

    /**
     * Delay before the next attempt: the exponential backoff, extended to the {@code Retry-After}
     * delay the server asked for when the failure carries one.
     *
     * <p>Rule: {@code wait = min(max(backoff, retryAfter), MAX_RETRY_AFTER_MILLIS)}, where
     * {@code backoff = min(2000 ms, 200 ms << (attempt - 1))}. {@code max} keeps the plain backoff
     * as the floor so a small advertised value cannot make retries faster than before, and the
     * clamp bounds a hostile or misconfigured header. Because the header is untrusted input, the
     * seconds-to-milliseconds conversion is clamped before multiplying so it cannot overflow.
     * Anything that is not an {@link HttpStatusException} without a usable header keeps exactly
     * the previous backoff.
     */
    private static long retryDelayMillis(Exception exception, int attempt) {
        long backoffMillis = Math.min(2000L, 200L << (attempt - 1));
        if (!(exception instanceof HttpStatusException)) {
            return backoffMillis;
        }
        Long retryAfterSeconds = ((HttpStatusException) exception).getRetryAfterSeconds();
        if (retryAfterSeconds == null) {
            return backoffMillis;
        }
        long seconds = retryAfterSeconds.longValue();
        long retryAfterMillis = seconds >= MAX_RETRY_AFTER_MILLIS / 1000L
                ? MAX_RETRY_AFTER_MILLIS
                : seconds * 1000L;
        return Math.min(MAX_RETRY_AFTER_MILLIS, Math.max(backoffMillis, retryAfterMillis));
    }

    private static boolean isRetryable(Exception exception) {
        if (exception instanceof TranslationEndpointUnavailableException) {
            return false;
        }
        if (exception instanceof HttpStatusException) {
            return ((HttpStatusException) exception).isRetryable();
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
