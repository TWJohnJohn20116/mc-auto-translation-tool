package org.universaltranslator.core.provider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

/**
 * Bounds one {@code text/event-stream} response while it is being read.
 *
 * <p>{@link org.universaltranslator.core.net.ServerSentEvents} stops when the endpoint closes the body,
 * when the handler asks to stop, when a payload bound is exceeded, or when the stream has been silent
 * for its budget — but every one of those checks lives inside the loop body, so a worker blocked in a
 * socket read never reaches them. The per-read socket timeout does not help either: a {@code :}
 * keep-alive comment resets it. The translation worker then stays in that read loop for the rest of
 * the session, and {@code TranslationCoordinator.close()} cannot wake it, because interrupting a
 * thread blocked on a socket read does nothing.
 *
 * <p>This stream turns the two exits the parser cannot supply into client-side ones without touching
 * it:
 *
 * <ul>
 *   <li>progress budget — a read that delivers no byte for that long throws, so a slow answer that
 *       keeps arriving is never cut off;
 *   <li>cancellation — an interrupt is observed on the next read instead of being lost.
 * </ul>
 *
 * <p>Byte bounds are deliberately not repeated here. The parser already counts the payload, the line
 * and the non-payload bytes, and a second, larger cap in this layer never triggered first — it only
 * made it unclear which layer's message a user was reading.
 *
 * <p>A stream that delivers data is making progress, so the budget bounds silence rather than length:
 * a 4096-token answer from a 15 token/s endpoint needs 273 s, and a budget on the whole stream turned
 * that healthy answer into a timeout whose retry re-sent the entire paid prompt. The caller supplies
 * the budget, and it is the one {@code HttpJsonClient} already computed for this response — so
 * {@code api-read-timeout-ms} decides it instead of a second, hidden constant.
 *
 * <p>The provider owns this because the parser is shared with every other stream consumer; the
 * parser-side version of the same guards is recorded as a handoff in
 * {@code reports/audit2-fixes/F-a04.md}.
 */
final class StreamWatchdog extends FilterInputStream {
    /**
     * Progress budget one event stream gets when the caller has no deadline of its own. It is
     * deliberately above {@code api-read-timeout-ms}'s default (120 s) so a slow-but-progressing
     * answer still lands, and far below "forever" so a stream that stops delivering cannot retire a
     * worker.
     */
    static final long DEADLINE_MILLIS = 180000L;

    private final long progressBudgetMillis;
    private final long progressBudgetNanos;
    private long progressDeadlineNanos;

    private StreamWatchdog(InputStream delegate, long progressBudgetMillis) {
        super(delegate);
        this.progressBudgetMillis = Math.max(1L, progressBudgetMillis);
        this.progressBudgetNanos = TimeUnit.MILLISECONDS.toNanos(this.progressBudgetMillis);
        this.progressDeadlineNanos = System.nanoTime() + this.progressBudgetNanos;
    }

    /** Wraps one response body with the default budget. */
    static InputStream arm(InputStream body) {
        return arm(body, DEADLINE_MILLIS);
    }

    /**
     * Wraps one response body.
     *
     * <p>A {@code null} body used to come back as {@code null} and was then handed to the parser
     * anyway, whose first {@code read} threw a {@code NullPointerException} that escaped every retry
     * and fallback decision — not the "there was no body" answer the caller asked for.
     *
     * @param body stream handed over by {@code HttpJsonClient.StreamedResponse}
     * @param progressBudgetMillis how long one read may deliver nothing
     * @return the bounded stream
     * @throws IllegalStateException when the response carried no body at all
     */
    static InputStream arm(InputStream body, long progressBudgetMillis) {
        if (body == null) {
            throw new IllegalStateException("streamed response carried no body");
        }
        return new StreamWatchdog(body, progressBudgetMillis);
    }

    @Override
    public int read() throws IOException {
        checkBudget();
        int value = super.read();
        if (value >= 0) {
            noteProgress();
        }
        return value;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        // Routed through the three-argument form so one read is noted exactly once.
        return read(buffer, 0, buffer.length);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        checkBudget();
        int count = super.read(buffer, offset, length);
        if (count > 0) {
            noteProgress();
        }
        return count;
    }

    private void noteProgress() {
        progressDeadlineNanos = System.nanoTime() + progressBudgetNanos;
    }

    /**
     * Fails the read before it blocks when the stream has been silent for the whole budget or the
     * caller was cancelled.
     */
    private void checkBudget() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Translation event stream was cancelled");
        }
        if (System.nanoTime() - progressDeadlineNanos >= 0L) {
            throw new LimitExceededException("Translation event stream received no data for "
                    + progressBudgetMillis + " ms");
        }
    }

    /**
     * A stream that outlived this budget.
     *
     * <p>The type exists so a provider can tell "this endpoint cannot stream within any budget"
     * apart from "the connection broke once": only the former is worth remembering, because only it
     * would be paid again by every later line.
     */
    static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        private LimitExceededException(String message) {
            super(message);
        }
    }

    /**
     * Whether the failure was one of this watchdog's limits, seen through the cause chain.
     *
     * @param failure what the stream read threw
     * @return {@code true} when the endpoint, not the network, is the problem
     */
    static boolean isLimitExceeded(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof LimitExceededException) {
                return true;
            }
        }
        return false;
    }
}
