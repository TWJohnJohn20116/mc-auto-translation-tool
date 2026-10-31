package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationProviderStatus;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** Tries a privacy-preserving primary provider before an optional online fallback. */
public final class FallbackTranslationProvider
        implements TranslationProvider, TranslationProviderStatus, AutoCloseable {
    /**
     * Transient primary failures tolerated before the primary is skipped for a while. Below this the
     * cost of trying it is one failed call, which is cheaper than a short-circuit that would keep a
     * working engine idle.
     */
    private static final int FAILURES_BEFORE_SHORT_CIRCUIT = 3;
    /** Short-circuit length for the first burst of failures. */
    private static final long SHORT_CIRCUIT_BASE_MILLIS = 5000L;
    /** Upper bound of the short-circuit, so a recovered engine is noticed within a minute. */
    private static final long SHORT_CIRCUIT_MAX_MILLIS = 60000L;

    private final TranslationProvider primary;
    private final TranslationProvider fallback;
    private volatile String lastStatus = "";
    /**
     * Consecutive transient primary failures. Read and written without a lock: the value only
     * decides when to stop trying the primary, and four workers racing it can at worst trip the
     * short-circuit one request early.
     */
    private volatile int consecutiveFailures;
    /**
     * {@link System#nanoTime()} instant until which the primary is skipped.
     *
     * <p>The wall clock is corrected by NTP, by the user and by a resume from sleep, and a step
     * backwards there turned an expired short-circuit into an unexpired one: the local engine was
     * skipped and every line went to the online fallback until the clock caught up. {@code nanoTime} is
     * the clock {@link RequestGate} already uses for the same reason; meaningless until
     * {@link #primaryBlocked} is set, because {@code nanoTime()} may start below any fixed value.
     */
    private volatile long primaryBlockedUntilNanos;
    /** Whether an instant was ever recorded; {@code nanoTime()} may be negative on some platforms. */
    private volatile boolean primaryBlocked;

    public FallbackTranslationProvider(TranslationProvider primary, TranslationProvider fallback) {
        if (primary == null || fallback == null) {
            throw new IllegalArgumentException("Both primary and fallback providers are required");
        }
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String id() {
        return "fallback:" + primary.id() + ":" + fallback.id();
    }

    @Override
    public String status() {
        if (!lastStatus.isEmpty()) {
            return lastStatus;
        }
        return primary instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) primary).status()
                : "主翻译服务运行中";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        Exception primaryFailure = null;
        if (!primaryShortCircuited()) {
            try {
                String translated = primary.translate(request);
                consecutiveFailures = 0;
                updateStatus(primaryStatus());
                return translated;
            } catch (Exception failure) {
                if (isInterruption(failure)) {
                    // The session is being cancelled (TranslationCoordinator.close() interrupts its
                    // workers). Never hand the pending text to the online fallback after that: restore
                    // the interrupt flag and let the caller unwind instead of transmitting off-device.
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                if (!FailurePolicy.mayFallBack(failure)) {
                    // A text that does not fit the local context, a rejected answer or a protocol
                    // error fails the same way anywhere. Falling back used to send exactly those texts
                    // to the online API — a privacy decision the user never made — and every later
                    // encounter with the same text repeated it.
                    updateStatus("主翻译服务无法处理这段文字");
                    throw failure;
                }
                recordPrimaryFailure();
                primaryFailure = failure;
            }
        }
        try {
            String translated = fallback.translate(request);
            updateStatus("主翻译服务失败，已使用 API 回退");
            return translated;
        } catch (Exception fallbackFailure) {
            updateStatus("主翻译服务和 API 回退均失败");
            if (primaryFailure != null) {
                fallbackFailure.addSuppressed(primaryFailure);
            }
            throw fallbackFailure;
        }
    }

    /** Whether the primary is being skipped after a burst of transient failures. */
    private boolean primaryShortCircuited() {
        if (!primaryBlocked) {
            return false;
        }
        return System.nanoTime() - primaryBlockedUntilNanos < 0L;
    }

    /**
     * Counts one transient primary failure and short-circuits the primary once they pile up.
     *
     * <p>Without this every line of a broken engine paid a full failed attempt before the fallback
     * answered: lock contention, an exception, and a round trip of latency, per line, for as long as
     * the engine stayed down. The short-circuit doubles from five seconds and stops at a minute, so a
     * recovered engine is picked up again quickly and a permanently broken one costs one probe per
     * minute instead of one per line.
     */
    private void recordPrimaryFailure() {
        int failures = consecutiveFailures + 1;
        consecutiveFailures = failures;
        if (failures >= FAILURES_BEFORE_SHORT_CIRCUIT) {
            int step = Math.min(4, failures - FAILURES_BEFORE_SHORT_CIRCUIT);
            long backoff = Math.min(SHORT_CIRCUIT_MAX_MILLIS, SHORT_CIRCUIT_BASE_MILLIS << step);
            primaryBlockedUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoff);
            // Set after the instant, so a reader that sees the flag also sees the deadline it belongs to.
            primaryBlocked = true;
        }
    }

    /** Writes the status only when it actually changed; this runs on every translated line. */
    private void updateStatus(String next) {
        if (!next.equals(lastStatus)) {
            lastStatus = next;
        }
    }

    private String primaryStatus() {
        return primary instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) primary).status()
                : "主翻译服务运行中";
    }

    private static boolean isInterruption(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
            // SocketTimeoutException extends InterruptedIOException, but a read or connect timeout
            // is a network failure, not a cancellation. Treating it as one made api-fallback fail
            // in exactly the situation it exists for: the local model too slow to answer within
            // the loopback client's read timeout. Check the subclass first.
            if (current instanceof SocketTimeoutException) {
                return false;
            }
            if (current instanceof InterruptedIOException) {
                return true;
            }
        }
        return Thread.currentThread().isInterrupted();
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        failure = closeOne(primary, failure);
        failure = closeOne(fallback, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static Exception closeOne(TranslationProvider provider, Exception previous) {
        if (!(provider instanceof AutoCloseable)) {
            return previous;
        }
        try {
            ((AutoCloseable) provider).close();
        } catch (Exception exception) {
            if (previous == null) {
                return exception;
            }
            previous.addSuppressed(exception);
        }
        return previous;
    }
}
