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
 * bounded the same way.
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
     * Reads the stream to its end, or to the first payload whose handler returned {@code false}.
     *
     * @return number of payload characters handed to the handler
     * @throws IOException when the stream fails or exceeds a bound
     */
    public static int read(InputStream input, PayloadHandler handler) throws IOException {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        ByteArrayOutputStream line = new ByteArrayOutputStream(READ_BUFFER_BYTES);
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
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
                String payload = dataPayload(line.toByteArray());
                line.reset();
                if (payload == null) {
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
                handler.onData(payload);
            }
        }
        return total;
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
