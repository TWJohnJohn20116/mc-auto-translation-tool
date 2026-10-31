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
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLException;
import org.universaltranslator.core.TextPreview;
import org.universaltranslator.core.UserAgent;

/** Bounded Java 8 HTTP client used to avoid shipping a large networking dependency. */
public final class HttpJsonClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    /** How much of an error body is read for the provider's own message. */
    private static final int MAXIMUM_ERROR_BYTES = 64 * 1024;
    /** Longest server supplied redirect target that may reach an exception message. */
    private static final int MAXIMUM_REDIRECT_CHARS = 200;
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
    /**
     * Fewest bytes one stall window of a response body has to deliver.
     *
     * <p>The silence check alone can never decide anything: {@code api-read-timeout-ms} ends a read
     * before one window does, so the only read that ever reached it had already returned a whole
     * buffer. Counting what a window actually delivered is what separates an answer that is merely
     * slow from a peer that trickles bytes to hold a worker.
     */
    private static final int MINIMUM_BYTES_PER_WINDOW = 4 * 1024;
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
        // The ceiling is taken from this client's own budget, not from the class-wide maximum: a
        // client configured to fail fast used to keep a worker for thirty minutes anyway.
        long bodyDeadlineNanos = System.nanoTime() + absoluteBodyBudgetNanos();
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
            // The status decides what the failure means, so it is thrown before the error body is
            // read: a body over the cap used to replace a 401 with a size IOException, which the
            // retry and fallback policies both treat as worth another paid attempt.
            if (status < 200 || status >= 300) {
                HttpStatusException failure = statusFailure(connection, status, deadlineNanos,
                        bodyDeadlineNanos, timeBudgetNanos());
                // An error body that was read to its end leaves the socket reusable: the transport
                // already paid for the whole response, so tearing the connection down only bought a
                // TCP and TLS handshake on every retry of a 429 or a 503.
                if (failure.isBodyDrained()) {
                    connectionReusable = true;
                }
                throw failure;
            }
            InputStream stream = connection.getInputStream();
            String response = stream == null ? "" : readBounded(decoded(stream), deadlineNanos,
                    bodyDeadlineNanos, timeBudgetNanos());
            // The body was read to the end and closed, so the connection may be pooled. Any other
            // exit (non-2xx status, exception, unread body) still disconnects below.
            if (stream != null) {
                connectionReusable = true;
            }
            return response;
        } catch (ConnectException refused) {
            throw TranslationEndpointUnavailableException.connectionRefused(endpoint, refused);
        } catch (UnknownHostException unresolved) {
            throw TranslationEndpointUnavailableException.unknownHost(endpoint, unresolved);
        } catch (NoRouteToHostException unreachable) {
            throw TranslationEndpointUnavailableException.unknownHost(endpoint, unreachable);
        } catch (SSLException tls) {
            throw TranslationEndpointUnavailableException.tlsFailure(endpoint, tls);
        } finally {
            if (!connectionReusable) {
                connection.disconnect();
            }
        }
    }

    /**
     * Builds the failure for a non-2xx status without ever letting the body decide the status.
     *
     * @param deadlineNanos absolute {@link System#nanoTime()} value to stop reading the body at
     * @param absoluteDeadlineNanos absolute {@link System#nanoTime()} value no body may outlive
     * @param windowNanos length of one no-progress window of that body
     */
    private static HttpStatusException statusFailure(
            HttpURLConnection connection,
            int status,
            long deadlineNanos,
            long absoluteDeadlineNanos,
            long windowNanos
    ) {
        Long retryAfterSeconds = parseRetryAfterSeconds(connection.getHeaderField("Retry-After"));
        if (status >= 300 && status < 400) {
            // Redirects are deliberately not followed, so a 3xx has to name the address the service
            // moved to; otherwise every line fails with a status that reads like a server refusal.
            String location = connection.getHeaderField("Location");
            String target = location == null || location.trim().isEmpty()
                    ? null : displayText(location.trim());
            return new HttpStatusException(status, "Translation endpoint was redirected (HTTP "
                    + status
                    + (target == null
                            ? "); update the endpoint to the address the service uses now"
                            : ") to " + target + "; update the endpoint to that address"),
                    retryAfterSeconds);
        }
        String response;
        boolean drained;
        try {
            InputStream error = connection.getErrorStream();
            BoundedBody body = error == null
                    ? new BoundedBody("", true)
                    : readBounded(decoded(error), deadlineNanos, absoluteDeadlineNanos,
                            windowNanos, MAXIMUM_ERROR_BYTES, true);
            response = body.text;
            drained = body.drained;
        } catch (IOException unreadable) {
            return new HttpStatusException(status,
                    "Translation service returned HTTP " + status
                            + " (its error body could not be read)",
                    retryAfterSeconds, unreadable);
        }
        String providerError = providerErrorText(response);
        HttpStatusException failure = new HttpStatusException(status,
                "Translation service returned HTTP " + status
                        + (providerError == null ? "" : ": " + providerError), retryAfterSeconds);
        if (drained) {
            failure.markBodyDrained();
        }
        return failure;
    }

    /** One-line, bounded rendering of a server supplied header value. */
    private static String displayText(String value) {
        return TextPreview.oneLine(value, MAXIMUM_REDIRECT_CHARS);
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
     * The sniffed bytes are pushed back in both directions, so whatever happens next reads the body
     * from its first byte.
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
            // The gzip constructor reads and compares the magic itself, so the two bytes consumed by
            // the sniff have to be back in the stream. Without this the constructor compared the
            // header fields and every compressed body failed as "Not in GZIP format", which is a
            // retryable IOException — three paid attempts per line, all of them doomed.
            probe.unread(head, 0, read);
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
        // Set only when a non-2xx body was read to its end, so the socket can go back to the pool
        // instead of being torn down on every retried 429 or 503.
        boolean connectionReusable = false;
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
                HttpStatusException failure = statusFailure(connection, status, deadlineNanos,
                        System.nanoTime() + absoluteBodyBudgetNanos(), timeBudgetNanos());
                if (failure.isBodyDrained()) {
                    connectionReusable = true;
                }
                throw failure;
            }
            InputStream stream = connection.getInputStream();
            StreamedResponse response = new StreamedResponse(
                    connection, status, connection.getContentType(),
                    stream == null ? new ByteArrayInputStream(new byte[0])
                            : new DeadlineInputStream(stream, connection, deadlineNanos,
                                    timeBudgetNanos(), absoluteBodyBudgetNanos()),
                    deadlineNanos);
            handedOver = true;
            return response;
        } catch (ConnectException refused) {
            throw TranslationEndpointUnavailableException.connectionRefused(endpoint, refused);
        } catch (UnknownHostException unresolved) {
            throw TranslationEndpointUnavailableException.unknownHost(endpoint, unresolved);
        } catch (NoRouteToHostException unreachable) {
            throw TranslationEndpointUnavailableException.unknownHost(endpoint, unreachable);
        } catch (SSLException tls) {
            throw TranslationEndpointUnavailableException.tlsFailure(endpoint, tls);
        } finally {
            // The connection stays open only while the caller still holds the response stream, and a
            // non-2xx body that was read to its end is no exception to that: nobody holds it and
            // nothing is left to read, so tearing it down only paid for another handshake.
            if (!handedOver && !connectionReusable) {
                connection.disconnect();
            }
        }
    }

    private long timeBudgetNanos() {
        return timeBudgetMillis * 1000L * 1000L;
    }

    /**
     * Ceiling for one response body, derived from this client's own budget.
     *
     * <p>Reading the class-wide maximum here decoupled the ceiling from
     * {@code api-read-timeout-ms}: a client configured to fail fast kept a worker for thirty
     * minutes, thirty times the budget it was created with. Twice the budget still leaves room for
     * an answer produced after a long generation while staying proportional to the setting.
     */
    private long absoluteBodyBudgetNanos() {
        return Math.min(MAXIMUM_TIME_BUDGET_MILLIS, 2L * timeBudgetMillis) * 1000L * 1000L;
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
            if (!isTokenCharacter(name.charAt(index))) {
                throw new IllegalArgumentException(
                        "HTTP header name contains an unsupported character: " + name);
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

    /**
     * True for the RFC 7230 {@code tchar} set, which is exactly what {@code setRequestProperty}
     * accepts in a name.
     *
     * <p>Accepting less than the settings loader did moved the failure to the hot path: a legal
     * name such as {@code X_API_Key} was saved and then rejected on every request.
     */
    private static boolean isTokenCharacter(char character) {
        if ((character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')) {
            return true;
        }
        return "!#$%&'*+.^_`|~-".indexOf(character) >= 0;
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

        /**
         * Absolute {@link System#nanoTime()} value the first no-progress window of this body ends at.
         *
         * <p>The transport re-arms that window whenever the body delivers bytes, so the distance to
         * now is the budget a stall is measured against — not a bound on how long the answer may
         * take. A stream consumer passes it to
         * {@link ServerSentEvents#read(InputStream, ServerSentEvents.PayloadHandler, long)} so one
         * setting decides the deadline for every provider. The value is read from the body wrapper
         * that does the re-arming, so a consumer that asks mid-stream gets the window that is
         * running now rather than the one computed at construction.
         */
        public long getDeadlineNanos() {
            InputStream body = this.body;
            if (body instanceof DeadlineInputStream) {
                return ((DeadlineInputStream) body).progressDeadlineNanos;
            }
            return deadlineNanos;
        }

        /**
         * How long this body may stay silent before the transport tears it down.
         *
         * <p>The single decision point for a consumer that arms a stall watchdog of its own: a
         * constant there would override {@code api-read-timeout-ms} again, which is how three
         * providers ended up disagreeing about one setting. This is the length of the window, not
         * the time left in it: a consumer that arms its watchdog mid-stream needs the budget itself,
         * and one that recomputed it from a shrinking remainder ended up with a millisecond.
         */
        public long getProgressBudgetMillis() {
            InputStream body = this.body;
            if (body instanceof DeadlineInputStream) {
                return Math.max(1L, ((DeadlineInputStream) body).progressBudgetNanos / 1000000L);
            }
            return Math.max(1L, (deadlineNanos - System.nanoTime()) / 1000000L);
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
     * Bounds a response body on silence rather than on total length.
     *
     * <p>The read timeout is per {@code read()}, so it cannot stop a stream that keeps trickling
     * bytes. This wrapper asks the budget before every read and, once it passes, disconnects the
     * connection — the only action that unblocks the currently blocked read — and reports the
     * overrun as an {@link IOException} to the caller.
     *
     * <p>The budget is re-armed by every read that delivers a byte, which is what makes
     * {@code api-read-timeout-ms} mean the same thing here as it does in the parser: a healthy
     * answer that takes longer than one budget in total is not an error, a silent one is. A wall-clock
     * ceiling still exists so a peer that trickles one byte per budget cannot keep a worker forever.
     */
    private static final class DeadlineInputStream extends java.io.FilterInputStream {
        private final HttpURLConnection connection;
        private final long progressBudgetNanos;
        private final long absoluteDeadlineNanos;
        /** Re-armed by the reading thread and read by a watchdog thread, so it is published. */
        private volatile long progressDeadlineNanos;

        private DeadlineInputStream(
                InputStream body,
                HttpURLConnection connection,
                long deadlineNanos,
                long progressWindowNanos,
                long absoluteBudgetNanos
        ) {
            super(body);
            this.connection = connection;
            long now = System.nanoTime();
            // The window is a length, not what is left of the request budget: a stream whose headers
            // arrived after the request deadline used to get a one-nanosecond window and lose its
            // first event. A deadline still in the future keeps capping the first window.
            this.progressBudgetNanos = Math.max(1L, progressWindowNanos);
            this.progressDeadlineNanos = deadlineNanos - now > 0L
                    ? Math.min(deadlineNanos, now + this.progressBudgetNanos)
                    : now + this.progressBudgetNanos;
            // Both halves follow this client's own budget now: the ceiling used to be the class-wide
            // thirty minutes, which left a fail-fast client holding a worker thirty times longer on
            // the streaming path than on the ordinary one.
            this.absoluteDeadlineNanos = now + Math.max(this.progressBudgetNanos, absoluteBudgetNanos);
        }

        @Override
        public int read() throws IOException {
            requireTimeBudget();
            int value = super.read();
            if (value >= 0) {
                noteProgress();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            requireTimeBudget();
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                noteProgress();
            }
            return count;
        }

        private void noteProgress() {
            progressDeadlineNanos = System.nanoTime() + progressBudgetNanos;
        }

        private void requireTimeBudget() throws IOException {
            long now = System.nanoTime();
            if (now - absoluteDeadlineNanos > 0L) {
                connection.disconnect();
                throw new TranslationBudgetExceededException(
                        "Response exceeded its total time budget");
            }
            if (now - progressDeadlineNanos > 0L) {
                connection.disconnect();
                throw new TranslationBudgetExceededException("Response received no data for "
                        + (progressBudgetNanos / 1000000L) + " ms");
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
     * Reads a body up to {@link #MAX_RESPONSE_BYTES} and fails it once a whole {@code windowNanos}
     * passes without progress.
     *
     * <p>The byte bound alone never terminates a body that arrives slowly: a peer sending one byte
     * just before every read timeout keeps the request alive forever while staying under the size
     * limit. The deadline turns that into an ordinary read failure — and it bounds <em>silence</em>,
     * not the total time: a reasoning model that takes longer than four read timeouts to produce a
     * long answer while the socket keeps delivering is a healthy answer, not a failed one. A body
     * that stops arriving is still failed, and an absolute ceiling keeps a peer that trickles one
     * byte per window from holding a worker forever. This is the same two-bound shape
     * {@link DeadlineInputStream} uses for a streamed body, so one setting means one thing on both
     * paths.
     *
     * @param deadlineNanos absolute {@link System#nanoTime()} value the request budget ends at; one
     *                      still in the future caps the first window
     * @param absoluteDeadlineNanos absolute {@link System#nanoTime()} value the whole body has to
     *                      arrive by, derived from the caller's own budget
     * @param windowNanos length of one no-progress window, so it is a length and never what is left
     *                      of the request budget
     */
    private static String readBounded(
            InputStream input,
            long deadlineNanos,
            long absoluteDeadlineNanos,
            long windowNanos
    ) throws IOException {
        return readBounded(input, deadlineNanos, absoluteDeadlineNanos, windowNanos,
                MAX_RESPONSE_BYTES, false).text;
    }

    /**
     * Reads a body up to {@code limit} bytes and fails it once a whole {@code windowNanos} passes
     * without progress.
     *
     * @param truncate {@code true} to keep what arrived at {@code limit} instead of failing on size,
     *         which is what an error body needs: the status matters more than its tail
     */
    private static BoundedBody readBounded(
            InputStream input,
            long deadlineNanos,
            long absoluteDeadlineNanos,
            long windowNanos,
            int limit,
            boolean truncate
    ) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            // The deadline handed in bounds silence: an answer that keeps arriving is never cut off at
            // a fixed wall clock, which is the same shape DeadlineInputStream uses for a streamed
            // body. The absolute ceiling is passed in by the caller so it follows that client's own
            // budget, and the window counts bytes because a silence check on its own could never
            // fire: the read timeout ends every read before one window is over.
            // The window is a length, so the first one starts with the body. Recomputing it from what
            // was left of the request budget collapsed it to one nanosecond on a body that began
            // after that budget, which then failed a short answer that had already arrived in full.
            long startedNanos = System.nanoTime();
            long progressBudgetNanos = Math.max(1L, windowNanos);
            long progressDeadlineNanos = deadlineNanos - startedNanos > 0L
                    ? Math.min(deadlineNanos, startedNanos + progressBudgetNanos)
                    : startedNanos + progressBudgetNanos;
            long windowBytes = 0L;
            while ((count = source.read(buffer)) >= 0) {
                total += count;
                if (total > limit) {
                    if (!truncate) {
                        throw new IOException("Translation response exceeded 1 MiB");
                    }
                    output.write(buffer, 0, count - (total - limit));
                    // The rest of this body was never read, so the socket cannot be pooled.
                    return new BoundedBody(
                            new String(output.toByteArray(), StandardCharsets.UTF_8), false);
                }
                long now = System.nanoTime();
                if (now - absoluteDeadlineNanos > 0L) {
                    throw new TranslationBudgetExceededException(
                            "Translation response exceeded its total time budget");
                }
                windowBytes += count;
                if (now - progressDeadlineNanos > 0L) {
                    if (windowBytes < MINIMUM_BYTES_PER_WINDOW) {
                        throw new TranslationBudgetExceededException(
                                "Translation response delivered only " + windowBytes
                                        + " bytes within " + (progressBudgetNanos / 1000000L) + " ms");
                    }
                    progressDeadlineNanos = now + progressBudgetNanos;
                    windowBytes = 0L;
                }
                output.write(buffer, 0, count);
            }
            return new BoundedBody(new String(output.toByteArray(), StandardCharsets.UTF_8), true);
        }
    }

    /** One bounded read: the text it produced and whether the body was read to its end. */
    private static final class BoundedBody {
        private final String text;
        /** {@code true} only when the source reached EOF, so the socket may go back to the pool. */
        private final boolean drained;

        private BoundedBody(String text, boolean drained) {
            this.text = text;
            this.drained = drained;
        }
    }
}
