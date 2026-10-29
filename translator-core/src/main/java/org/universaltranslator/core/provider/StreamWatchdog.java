package org.universaltranslator.core.provider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/**
 * Bounds one {@code text/event-stream} response while it is being read.
 *
 * <p>{@link org.universaltranslator.core.net.ServerSentEvents} can only stop when the endpoint closes
 * the body, when the handler asks to stop, or when a payload bound is exceeded — and its payload
 * bound only counts {@code data:} characters, so an endpoint (or a relay, or a hijacked local proxy)
 * that sends a {@code : keep-alive} comment every few seconds and never closes is invisible to it.
 * The per-read socket timeout does not help either: every one of those comments resets it. The
 * translation worker then stays inside that read loop for the rest of the session, and
 * {@code TranslationCoordinator.close()} cannot wake it, because interrupting a thread blocked on a
 * socket read does nothing.
 *
 * <p>This stream turns all three exits into client-side ones without touching the parser:
 *
 * <ul>
 *   <li>wall-clock budget — the read that crosses the deadline throws;
 *   <li>cancellation — an interrupt is observed on the next read instead of being lost;
 *   <li>raw byte budget — the comments that the payload bound cannot see are still counted.
 * </ul>
 *
 * <p>The provider owns this because the parser is shared with every other stream consumer; the
 * parser-side version of the same guards is recorded as a handoff in
 * {@code reports/audit2-fixes/F-a04.md}.
 */
final class StreamWatchdog extends FilterInputStream {
    /**
     * Wall-clock budget one event stream may hold a translation worker. It is deliberately above
     * the default {@code api-read-timeout-ms} (120 s) so a slow-but-progressing answer still lands,
     * and far below "forever" so a keep-alive-only stream cannot retire a worker.
     */
    static final long DEADLINE_MILLIS = 180000L;
    /** Raw bytes one event stream may deliver, keep-alive comments included. */
    private static final long MAXIMUM_BYTES = 8L * 1024L * 1024L;

    private final long deadlineMillis;
    private final long deadlineNanos;
    private long delivered;

    private StreamWatchdog(InputStream delegate, long deadlineMillis) {
        super(delegate);
        this.deadlineMillis = deadlineMillis;
        this.deadlineNanos = System.nanoTime() + deadlineMillis * 1000000L;
    }

    /** Wraps one response body with the default budget. */
    static InputStream arm(InputStream body) {
        return arm(body, DEADLINE_MILLIS);
    }

    /**
     * Wraps one response body.
     *
     * @param body stream handed over by {@code HttpJsonClient.StreamedResponse}, or {@code null}
     * @param deadlineMillis wall-clock budget for the whole stream
     * @return the bounded stream, or {@code null} when there was no body
     */
    static InputStream arm(InputStream body, long deadlineMillis) {
        if (body == null) {
            return null;
        }
        return new StreamWatchdog(body, Math.max(1L, deadlineMillis));
    }

    @Override
    public int read() throws IOException {
        checkBudget();
        int value = super.read();
        if (value >= 0) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        // Routed through the three-argument form so one read is counted exactly once.
        return read(buffer, 0, buffer.length);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        checkBudget();
        int count = super.read(buffer, offset, length);
        if (count > 0) {
            count(count);
        }
        return count;
    }

    private void count(int count) throws IOException {
        delivered += count;
        if (delivered > MAXIMUM_BYTES) {
            throw new IOException("Translation event stream delivered more than "
                    + MAXIMUM_BYTES + " bytes");
        }
    }

    /**
     * Fails the read before it blocks when the stream is already over budget or the caller was
     * cancelled.
     */
    private void checkBudget() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Translation event stream was cancelled");
        }
        if (deadlineNanos - System.nanoTime() <= 0L) {
            throw new IOException("Translation event stream exceeded its "
                    + (deadlineMillis / 1000L) + "-second budget without finishing");
        }
    }
}
