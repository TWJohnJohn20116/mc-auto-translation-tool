package org.universaltranslator.core.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads a {@code text/event-stream} response and hands every {@code data:} payload to a callback.
 *
 * <p>The stream is consumed one line at a time off raw bytes rather than through a {@code Reader}:
 * a chunk boundary can fall in the middle of a line, and it can equally fall in the middle of a
 * multi-byte UTF-8 character. Buffering the bytes until the line terminator arrives means a
 * character is only ever decoded once all of its bytes are present, so neither case needs special
 * handling at the call site.
 *
 * <p>Both the accumulated payload size and the length of a single line are bounded, so a
 * misbehaving endpoint cannot make the client buffer without limit: the non-streaming path is
 * bounded the same way. Non-payload lines are counted separately and the read also fails once the
 * stream has stayed silent for its budget, because bytes alone never terminate a stream that only
 * sends keep-alives.
 *
 * <p>Comment lines ({@code :} keep-alives), empty event separators and every field other than
 * {@code data} are ignored. Multiple {@code data} lines inside one event are deliberately not
 * joined: the OpenAI-compatible endpoints this client talks to emit one JSON document per event,
 * and joining would produce a body no JSON parser accepts.
 */
public final class ServerSentEvents {
    /** Largest payload the reader accepts in total, matching the bounded non-streaming path. */
    public static final int MAXIMUM_TOTAL_CHARS = 1024 * 1024;
    /** Largest single line accepted, so one hostile line cannot exhaust memory on its own. */
    public static final int MAXIMUM_LINE_BYTES = 256 * 1024;
    /**
     * Largest total volume of lines that are not payloads.
     *
     * <p>They used to be free: the payload bound only ever counted {@code data:} content, so an
     * endpoint that answers with nothing but {@code :} keep-alives stayed inside every limit while
     * the read loop never returned.
     */
    public static final int MAXIMUM_IGNORED_BYTES = 256 * 1024;
    /**
     * How long a stream may stay silent when the caller supplies no deadline at all.
     *
     * <p>A fallback, not a policy: a production stream must pass the budget its own response was
     * issued with, because a constant here is a second, hidden limit that overrides
     * {@code api-read-timeout-ms}. Two providers still reached this overload, so the same setting
     * produced three different stall limits and a user who raised it kept losing the stream at a
     * fixed value. {@link HttpJsonClient.StreamedResponse#getDeadlineNanos()} is the number to pass.
     */
    public static final long DEFAULT_TIME_BUDGET_MILLIS = 5 * 60 * 1000L;

    private static final long DEFAULT_TIME_BUDGET_NANOS = DEFAULT_TIME_BUDGET_MILLIS * 1000L * 1000L;
    private static final int READ_BUFFER_BYTES = 4096;

    /** Receives every {@code data:} payload until it asks to stop. */
    public interface PayloadHandler {
        /**
         * @param payload text after the {@code data:} prefix and one optional space
         * @return {@code false} to stop reading, for example after a {@code [DONE]} sentinel
         */
        boolean onData(String payload) throws IOException;
    }

    private ServerSentEvents() {
    }

    /**
     * Reads the stream to its end, or to the first payload whose handler returned {@code false},
     * under {@link #DEFAULT_TIME_BUDGET_MILLIS}.
     *
     * <p>Fallback for a stream with no transport deadline of its own (tests, an in-memory body). A
     * response body handed over by {@link HttpJsonClient#postStreaming} must use
     * {@link #read(InputStream, PayloadHandler, HttpJsonClient.StreamedResponse)} instead.
     *
     * @return number of payload characters handed to the handler
     * @throws IOException when the stream fails or exceeds a bound
     */
    public static int read(InputStream input, PayloadHandler handler) throws IOException {
        return read(input, handler, System.nanoTime() + DEFAULT_TIME_BUDGET_NANOS);
    }

    /**
     * Reads the stream to its end, to the first payload whose handler returned {@code false}, or
     * until the stream has stayed silent for the budget {@code deadlineNanos} encodes.
     *
     * <p>Only silence needs a bound here: a socket read timeout is re-armed on every single
     * {@code read()}, so an endpoint that sends a {@code :} keep-alive comment every so often — what
     * a proxy in front of a stuck upstream does — keeps one worker thread inside this loop forever
     * without ever tripping it, while an answer that keeps arriving is making progress.
     *
     * @param deadlineNanos absolute {@link System#nanoTime()} value a silent stream fails at; the
     *         distance to now is the budget a stall is measured against
     * @return number of payload characters handed to the handler
     * @throws IOException when the stream fails, exceeds a bound, or stops delivering data
     */
    public static int read(InputStream input, PayloadHandler handler, long deadlineNanos)
            throws IOException {
        if (input == null) {
            // Reported where the body actually goes missing: as a plain NullPointerException it
            // escaped every retry and fallback decision as an unchecked failure.
            throw new IllegalStateException("Server-sent event stream carried no body");
        }
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        ByteArrayOutputStream line = new ByteArrayOutputStream(READ_BUFFER_BYTES);
        long budgetNanos = deadlineNanos - System.nanoTime();
        long stallDeadlineNanos = deadlineNanos;
        // Whether the line being assembled can carry a payload: a ":" line is a keep-alive, and
        // counting it as progress would let a stream that only pings never run out of budget.
        boolean lineCarriesPayload = false;
        int total = 0;
        long ignored = 0L;
        int count;
        while (true) {
            requireProgress(stallDeadlineNanos, budgetNanos);
            count = input.read(buffer);
            if (count < 0) {
                break;
            }
            boolean progressed = false;
            for (int index = 0; index < count; index++) {
                int value = buffer[index] & 0xFF;
                if (value != '\n' && value != '\r') {
                    if (line.size() >= MAXIMUM_LINE_BYTES) {
                        throw new IOException(
                                "Server-sent event line exceeded " + MAXIMUM_LINE_BYTES + " bytes");
                    }
                    line.write(value);
                    if (line.size() == 1) {
                        lineCarriesPayload = value != ':';
                    }
                    progressed |= lineCarriesPayload;
                    continue;
                }
                if (line.size() == 0) {
                    // Empty line: the event separator between payloads, and the second half of CRLF.
                    continue;
                }
                int lineBytes = line.size();
                String payload = dataPayload(line.toByteArray());
                line.reset();
                if (payload == null) {
                    ignored += lineBytes;
                    if (ignored > MAXIMUM_IGNORED_BYTES) {
                        throw new IOException("Server-sent event stream exceeded "
                                + MAXIMUM_IGNORED_BYTES + " bytes of non-payload lines");
                    }
                    continue;
                }
                total += payload.length();
                if (total > MAXIMUM_TOTAL_CHARS) {
                    throw new IOException("Server-sent event stream exceeded "
                            + MAXIMUM_TOTAL_CHARS + " characters");
                }
                if (!handler.onData(payload)) {
                    return total;
                }
            }
            if (progressed) {
                long now = System.nanoTime();
                long next = now + budgetNanos;
                // Saturated, not wrapped: a caller that passes Long.MAX_VALUE means "no deadline",
                // and a wrapped value would fail the very next read.
                stallDeadlineNanos = next < now ? Long.MAX_VALUE : next;
            }
        }
        // A stream that ends without a trailing terminator still carries its last event.
        if (line.size() > 0) {
            String payload = dataPayload(line.toByteArray());
            if (payload != null) {
                total += payload.length();
                if (total > MAXIMUM_TOTAL_CHARS) {
                    throw new IOException("Server-sent event stream exceeded "
                            + MAXIMUM_TOTAL_CHARS + " characters");
                }
                if (!handler.onData(payload)) {
                    return total;
                }
            }
        }
        return total;
    }

    /**
     * Reads the stream under the budget the response itself was issued with.
     *
     * <p>This is the call a provider should use: {@code api-read-timeout-ms} then decides the stall
     * budget for every provider, instead of each one supplying a constant of its own — which is how
     * one setting produced three different limits. It also keeps the arithmetic in one place, so a
     * later provider cannot compute the window differently.
     *
     * @param input the body of {@code response}, typically wrapped by the caller's own watchdog
     * @param handler receives every payload
     * @param response the response whose body {@code input} reads
     * @return number of payload characters handed to the handler
     * @throws IOException when the stream fails, exceeds a bound, or stops delivering data
     */
    public static int read(
            InputStream input,
            PayloadHandler handler,
            HttpJsonClient.StreamedResponse response
    ) throws IOException {
        if (response == null) {
            throw new IllegalStateException("Streamed response is required for a streamed body");
        }
        return read(input, handler, response.getDeadlineNanos());
    }

    /**
     * Fails the read when the stream has delivered nothing for the whole budget.
     *
     * <p>The budget bounds how long the stream may stay silent, not how long the answer may be: a
     * 4096-token answer from a 15 token/s endpoint needs 273 s, so a budget on the whole stream
     * turned healthy answers into timeouts, and each retry re-sent the entire paid prompt. It is
     * reported as a socket timeout — the same shape the transport uses for its total budget — so a
     * caller classifying the failure sees a timeout and not a malformed answer.
     */
    private static void requireProgress(long stallDeadlineNanos, long budgetNanos) throws IOException {
        if (System.nanoTime() - stallDeadlineNanos > 0L) {
            throw new java.net.SocketTimeoutException("Server-sent event stream received no data for "
                    + (budgetNanos / 1000000L) + " ms");
        }
    }

    /**
     * Extracts the payload of one line, or {@code null} when the line is not a usable data field.
     *
     * @param line raw line bytes without its terminator
     */
    private static String dataPayload(byte[] line) {
        String text = new String(line, StandardCharsets.UTF_8);
        if (text.isEmpty() || text.charAt(0) == ':') {
            return null;
        }
        int separator = text.indexOf(':');
        String field = separator < 0 ? text : text.substring(0, separator);
        if (!"data".equals(field)) {
            return null;
        }
        String payload = separator < 0 ? "" : text.substring(separator + 1);
        // The specification allows one optional space after the colon; endpoints that omit it are
        // equally valid, so only a single leading space is removed.
        if (payload.startsWith(" ")) {
            payload = payload.substring(1);
        }
        return payload.isEmpty() ? null : payload;
    }
}
