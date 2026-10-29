package org.universaltranslator.core.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.universaltranslator.core.UserAgent;

/** Bounded Java 8 HTTP client used to avoid shipping a large networking dependency. */
public final class HttpJsonClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    /**
     * Lowest total budget the client will work with.
     *
     * <p>A {@code readTimeout} is re-armed on every {@code read()}, so it never bounds a request as
     * a whole: a peer that sends one byte just inside the timeout keeps a worker thread for as long
     * as it likes. The total budget is the bound that actually terminates the request.
     */
    private static final long MINIMUM_TIME_BUDGET_MILLIS = 60 * 1000L;
    private static final long MAXIMUM_TIME_BUDGET_MILLIS = 30 * 60 * 1000L;
    /** How many read timeouts one request may consume before the total budget applies. */
    private static final int READ_TIMEOUT_MULTIPLIER = 4;
    /** Headers the transport owns; letting a caller set them desynchronises request framing. */
    private static final java.util.Set<String> RESERVED_HEADERS = new java.util.HashSet<String>(
            java.util.Arrays.asList("host", "content-length", "transfer-encoding", "connection"));

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final long timeBudgetMillis;

    public HttpJsonClient(int connectTimeoutMillis, int readTimeoutMillis) {
        this(connectTimeoutMillis, readTimeoutMillis, 0);
    }

    /**
     * @param timeBudgetMillis total wall-clock budget for one request, or {@code 0} to derive it
     *         from {@code readTimeoutMillis}
     */
    public HttpJsonClient(int connectTimeoutMillis, int readTimeoutMillis, int timeBudgetMillis) {
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        long derived = timeBudgetMillis > 0
                ? timeBudgetMillis
                : (long) readTimeoutMillis * READ_TIMEOUT_MULTIPLIER;
        this.timeBudgetMillis = Math.min(MAXIMUM_TIME_BUDGET_MILLIS,
                Math.max(MINIMUM_TIME_BUDGET_MILLIS, derived));
    }

    public String post(URI endpoint, String jsonBody, String authorizationHeader) throws IOException {
        Map<String, String> headers = authorizationHeader == null || authorizationHeader.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.singletonMap("Authorization", authorizationHeader);
        return post(endpoint, jsonBody, headers);
    }

    public String post(URI endpoint, String jsonBody, Map<String, String> headers) throws IOException {
        return request("POST", endpoint, jsonBody, "application/json; charset=utf-8", headers);
    }

    public String postForm(URI endpoint, String formBody, Map<String, String> headers) throws IOException {
        return request("POST", endpoint, formBody,
                "application/x-www-form-urlencoded; charset=utf-8", headers);
    }

    /** Sends a bodyless GET, used to read small metadata documents such as a model catalog. */
    public String get(URI endpoint, Map<String, String> headers) throws IOException {
        return request("GET", endpoint, null, null, headers);
    }

    public String request(
            String method,
            URI endpoint,
            String bodyText,
            String contentType,
            Map<String, String> headers
    ) throws IOException {
        String requestMethod = method == null ? "" : method.trim().toUpperCase(java.util.Locale.ROOT);
        boolean bodyAllowed = "POST".equals(requestMethod) || "PUT".equals(requestMethod);
        if (!bodyAllowed && !"GET".equals(requestMethod)) {
            throw new IllegalArgumentException("Only GET, POST and PUT requests are supported");
        }
        String bodyValue = bodyText == null ? "" : bodyText;
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        // Set only when the response body was read to the end and closed, so the socket can go
        // back to the keep-alive pool instead of being torn down by disconnect().
        boolean connectionReusable = false;
        long deadlineNanos = System.nanoTime() + timeBudgetNanos();
        try {
            connection.setRequestMethod(requestMethod);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setDoOutput(bodyAllowed);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (bodyAllowed) {
                connection.setRequestProperty("Content-Type", contentType == null || contentType.trim().isEmpty()
                        ? "application/json; charset=utf-8" : contentType.trim());
            }
            connection.setRequestProperty("User-Agent", UserAgent.VALUE);
            // A translation reply is a few kilobytes of JSON that compresses to roughly a third, and
            // this is a per-request cost on every provider call.
            connection.setRequestProperty("Accept-Encoding", "gzip");
            applyHeaders(connection, headers);

            if (bodyAllowed) {
                byte[] body = bodyValue.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = stream == null ? "" : readBounded(decoded(stream), deadlineNanos);
            if (status < 200 || status >= 300) {
                String providerError = providerErrorText(response);
                throw new HttpStatusException(status, "Translation service returned HTTP " + status
                        + (providerError == null ? "" : ": " + providerError),
                        parseRetryAfterSeconds(connection.getHeaderField("Retry-After")));
            }
            // The body was read to the end and closed, so the connection may be pooled. Any other
            // exit (non-2xx status, exception, unread body) still disconnects below.
            if (stream != null) {
                connectionReusable = true;
            }
            return response;
        } catch (ConnectException refused) {
            throw TranslationEndpointUnavailableException.connectionRefused(endpoint, refused);
        } finally {
            if (!connectionReusable) {
                connection.disconnect();
            }
        }
    }

    /**
     * Extracts the provider's own explanation from a non-2xx body.
     *
     * <p>Every large provider nests it: OpenAI and its compatible relays send
     * {@code {"error":{"message":…,"type":…}}}, Anthropic {@code {"type":"error","error":{…}}} and
     * Gemini {@code {"error":{"code":400,"message":…}}}. A flat {@code readStringField(body,
     * "error")} never matches those because the value starts with {@code {}, so the exception
     * message degraded to a bare status code and every downstream check that reads the message
     * (the completion-budget learning in the OpenAI-compatible provider) silently stopped firing.
     *
     * @param response raw error body, possibly empty or not JSON at all
     * @return the provider's message, or {@code null} when the body carries none
     */
    private static String providerErrorText(String response) {
        String flat = JsonStrings.readStringField(response, "error");
        if (flat != null) {
            return flat;
        }
        try {
            Object root = JsonStrings.parse(response);
            if (root instanceof Map) {
                Object error = ((Map<?, ?>) root).get("error");
                if (error instanceof Map) {
                    Object message = ((Map<?, ?>) error).get("message");
                    if (message instanceof String && !((String) message).trim().isEmpty()) {
                        return ((String) message).trim();
                    }
                }
            }
        } catch (RuntimeException malformed) {
            // Not JSON (an HTML error page, an empty body): fall through to the legacy key.
        }
        return JsonStrings.readStringField(response, "Message");
    }

    /**
     * Unwraps a compressed response body.
     *
     * <p>Decided by the body's own first two bytes, never by {@code Content-Encoding} alone. Both
     * directions of mismatch happen in the wild: a proxy can drop the header on a gzipped body, and
     * a server can echo the header without compressing. Trusting the header turned the second case
     * into a failed translation, so the sniff is what decides and the header is only documentation.
     * The sniffed bytes are pushed back so a plain body is read unchanged.
     */
    private static InputStream decoded(InputStream stream) throws IOException {
        PushbackInputStream probe = new PushbackInputStream(stream, 2);
        byte[] head = new byte[2];
        int read = 0;
        while (read < head.length) {
            int count = probe.read(head, read, head.length - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        if (read == head.length && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
            return new GZIPInputStream(probe);
        }
        if (read > 0) {
            probe.unread(head, 0, read);
        }
        return probe;
    }

    /**
     * Posts one request and hands back the response without reading its body, with an optional
     * {@code Authorization} header. The {@link #post} entry points come in the same two shapes.
     *
     * @param endpoint destination
     * @param jsonBody request body
     * @param authorizationHeader value of the {@code Authorization} header, or {@code null} for none
     * @return the open response
     * @throws IOException on a transport failure, a non-2xx status, or a refused connection
     */
    public StreamedResponse postStreaming(URI endpoint, String jsonBody, String authorizationHeader)
            throws IOException {
        Map<String, String> headers = authorizationHeader == null || authorizationHeader.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.singletonMap("Authorization", authorizationHeader);
        return postStreaming(endpoint, jsonBody, headers);
    }

    /**
     * Posts one request and hands back the response without reading its body.
     *
     * <p>Used for {@code text/event-stream} answers, which are only useful while they are still
     * arriving. The caller owns the returned stream and must close it; until then the socket is
     * held open.
     *
     * <p>A non-2xx status is reported exactly like {@link #post} reports it — the error body is read
     * to its bound and an {@link HttpStatusException} is thrown — so a caller that falls back to the
     * ordinary path sees the same failure type it would have seen there.
     *
     * @param endpoint destination
     * @param jsonBody request body
     * @param headers extra request headers, never {@code null}
     * @return the open response
     * @throws IOException on a transport failure, a non-2xx status, or a refused connection
     */
    public StreamedResponse postStreaming(URI endpoint, String jsonBody, Map<String, String> headers)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        boolean handedOver = false;
        long deadlineNanos = System.nanoTime() + timeBudgetNanos();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            // Announced before the body is sent so a server that can stream does not fall back to
            // buffering the answer just because the client did not say it could read it.
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", UserAgent.VALUE);
            applyHeaders(connection, headers);
            byte[] body = (jsonBody == null ? "" : jsonBody).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream error = connection.getErrorStream();
                String response = error == null ? "" : readBounded(error, deadlineNanos);
                String providerError = providerErrorText(response);
                throw new HttpStatusException(status, "Translation service returned HTTP " + status
                        + (providerError == null ? "" : ": " + providerError),
                        parseRetryAfterSeconds(connection.getHeaderField("Retry-After")));
            }
            InputStream stream = connection.getInputStream();
            StreamedResponse response = new StreamedResponse(
                    connection, status, connection.getContentType(),
                    stream == null ? new ByteArrayInputStream(new byte[0])
                            : new DeadlineInputStream(stream, connection, deadlineNanos),
                    deadlineNanos);
            handedOver = true;
            return response;
        } catch (ConnectException refused) {
            throw TranslationEndpointUnavailableException.connectionRefused(endpoint, refused);
        } finally {
            // The connection stays open only while the caller still holds the response stream.
            if (!handedOver) {
                connection.disconnect();
            }
        }
    }

    private long timeBudgetNanos() {
        return timeBudgetMillis * 1000L * 1000L;
    }

    /**
     * Applies caller supplied headers.
     *
     * <p>Header injection was stopped only inside one provider's constructor, so every other
     * caller — and every provider added later — still handed arbitrary text to
     * {@code setRequestProperty}. On the JDK the legacy targets are built with, a value containing
     * CRLF was written to the request verbatim (request splitting); on a modern JDK the same value
     * escaped as an unchecked {@link IllegalArgumentException} from a method that only declares
     * {@link IOException}. The transport owns framing, so the check belongs here.
     *
     * @throws IllegalArgumentException on a malformed name, a reserved name, or a value that could
     *         terminate the header block
     */
    private static void applyHeaders(HttpURLConnection connection, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (name == null || value == null) {
                continue;
            }
            requireSafeHeader(name, value);
            connection.setRequestProperty(name, value);
        }
    }

    private static void requireSafeHeader(String name, String value) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("HTTP header name is required");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean token = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9') || character == '-';
            if (!token) {
                throw new IllegalArgumentException("HTTP header name contains an unsupported character");
            }
        }
        if (RESERVED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("HTTP header is reserved by the transport: " + name);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == '\0') {
                // The value itself is never echoed: it is a credential for most providers.
                throw new IllegalArgumentException("HTTP header value contains a line break: " + name);
            }
        }
    }

    /** A response whose body is still open, so a server-sent event stream can be read as it arrives. */
    public static final class StreamedResponse implements Closeable {
        private final HttpURLConnection connection;
        private final int statusCode;
        private final String contentType;
        private final InputStream body;
        private final long deadlineNanos;
        private boolean closed;

        private StreamedResponse(
                HttpURLConnection connection,
                int statusCode,
                String contentType,
                InputStream body,
                long deadlineNanos
        ) {
            this.connection = connection;
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.body = body;
            this.deadlineNanos = deadlineNanos;
        }

        public int getStatusCode() {
            return statusCode;
        }

        /** Raw {@code Content-Type} header value, or {@code null} when the server sent none. */
        public String getContentType() {
            return contentType;
        }

        public InputStream getBody() {
            return body;
        }

        /** Absolute {@link System#nanoTime()} value this response stops reading at. */
        public long getDeadlineNanos() {
            return deadlineNanos;
        }

        /**
         * Releases the socket and unblocks a reader stuck inside it.
         *
         * <p>A blocked socket read does not answer {@link Thread#interrupt()}, so closing the
         * stream is not enough to cancel a stream that stopped arriving: only
         * {@link HttpURLConnection#disconnect()} tears the socket down. Callers use this from a
         * watchdog when they decide the stream has outlived its useful life.
         */
        public void abort() {
            closed = true;
            try {
                body.close();
            } catch (IOException ignored) {
                // The socket is torn down below regardless.
            }
            connection.disconnect();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                body.close();
            } finally {
                // The body was not necessarily read to the end — a caller that stops at [DONE] never
                // reads the rest — so the socket cannot go back to the keep-alive pool. Tearing the
                // connection down also releases it if the endpoint keeps the stream open.
                connection.disconnect();
            }
        }
    }

    /**
     * Enforces a total time budget on a response body.
     *
     * <p>The read timeout is per {@code read()}, so it cannot stop a stream that keeps trickling
     * bytes. This wrapper asks the deadline before every read and, once it passes, disconnects the
     * connection — the only action that unblocks the currently blocked read — and reports the
     * overrun as an {@link IOException} to the caller.
     */
    private static final class DeadlineInputStream extends java.io.FilterInputStream {
        private final HttpURLConnection connection;
        private final long deadlineNanos;

        private DeadlineInputStream(InputStream body, HttpURLConnection connection, long deadlineNanos) {
            super(body);
            this.connection = connection;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int read() throws IOException {
            requireTimeBudget();
            return super.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            requireTimeBudget();
            return super.read(buffer, offset, length);
        }

        private void requireTimeBudget() throws IOException {
            if (System.nanoTime() - deadlineNanos > 0L) {
                connection.disconnect();
                throw new java.net.SocketTimeoutException(
                        "Response exceeded its total time budget");
            }
        }
    }

    /**
     * Parses a {@code Retry-After} header value into seconds.
     *
     * <p>Only the delta-seconds form is supported (for example {@code 120}); the HTTP-date form and
     * any other unparsable value are treated as unknown and reported as {@code null}. A malformed
     * header never fails the request itself.
     *
     * @param headerValue raw {@code Retry-After} header value, possibly {@code null}
     * @return the advertised delay in seconds, or {@code null} when it is unknown
     */
    private static Long parseRetryAfterSeconds(String headerValue) {
        String candidate = headerValue == null ? "" : headerValue.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(candidate);
            return seconds < 0 ? null : Long.valueOf(seconds);
        } catch (NumberFormatException notDeltaSeconds) {
            return null;
        }
    }

    /**
     * Reads a body up to {@link #MAX_RESPONSE_BYTES} and no further than {@code deadlineNanos}.
     *
     * <p>The byte bound alone never terminates a body that arrives slowly: a peer sending one byte
     * just before every read timeout keeps the request alive forever while staying under the size
     * limit. The deadline turns that into an ordinary read failure.
     *
     * @param deadlineNanos absolute {@link System#nanoTime()} value to stop at
     */
    private static String readBounded(InputStream input, long deadlineNanos) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Translation response exceeded 1 MiB");
                }
                if (System.nanoTime() - deadlineNanos > 0L) {
                    throw new java.net.SocketTimeoutException(
                            "Translation response exceeded its total time budget");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
