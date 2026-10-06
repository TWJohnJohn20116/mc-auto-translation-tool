package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.HttpStatusException;
import org.universaltranslator.core.net.TranslationEndpointUnavailableException;

import java.io.IOException;

/** Adds a configurable global request interval and bounded transient-error retries. */
final class ResilientTranslationProvider implements TranslationProvider, AutoCloseable {
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
                Thread.sleep(Math.min(2000L, 200L << (attempt - 1)));
            }
        }
        throw last == null ? new IllegalStateException("Translation failed") : last;
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
