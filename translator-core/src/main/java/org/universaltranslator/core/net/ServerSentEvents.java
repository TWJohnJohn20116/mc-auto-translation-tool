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
 * bounded the same way. Non-payload lines are counted separately and the whole read also runs
 * against a wall-clock budget, because bytes alone never terminate a stream that only sends
 * keep-alives.
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
    /** Wall-clock budget a stream may consume when the caller does not supply a deadline. */
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
     * @return number of payload characters handed to the handler
     * @throws IOException when the stream fails or exceeds a bound
     */
    public static int read(InputStream input, PayloadHandler handler) throws IOException {
        return read(input, handler, System.nanoTime() + DEFAULT_TIME_BUDGET_NANOS);
    }

    /**
     * Reads the stream to its end, to the first payload whose handler returned {@code false}, or
     * until {@code deadlineNanos} passes.
     *
     * <p>The deadline is what makes a stalled stream terminating at all. A socket read timeout is
     * re-armed on every single {@code read()}, so an endpoint that sends a {@code :} keep-alive
     * comment every so often — what a proxy in front of a stuck upstream does — keeps one worker
     * thread inside this loop forever without ever tripping it. The reader is the only place that
     * can see the stream is making no progress, so the budget lives here.
     *
     * @param deadlineNanos absolute {@link System#nanoTime()} value to stop at
     * @return number of payload characters handed to the handler
     * @throws IOException when the stream fails, exceeds a bound, or outlives its budget
     */
    public static int read(InputStream input, PayloadHandler handler, long deadlineNanos)
            throws IOException {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        ByteArrayOutputStream line = new ByteArrayOutputStream(READ_BUFFER_BYTES);
        int total = 0;
        long ignored = 0L;
        int count;
        while (true) {
            requireTimeBudget(deadlineNanos);
            count = input.read(buffer);
            if (count < 0) {
                break;
            }
            for (int index = 0; index < count; index++) {
                int value = buffer[index] & 0xFF;
                if (value != '\n' && value != '\r') {
                    if (line.size() >= MAXIMUM_LINE_BYTES) {
                        throw new IOException(
                                "Server-sent event line exceeded " + MAXIMUM_LINE_BYTES + " bytes");
                    }
                    line.write(value);
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

    private static void requireTimeBudget(long deadlineNanos) throws IOException {
        if (System.nanoTime() - deadlineNanos > 0L) {
            throw new IOException("Server-sent event stream exceeded its time budget");
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
