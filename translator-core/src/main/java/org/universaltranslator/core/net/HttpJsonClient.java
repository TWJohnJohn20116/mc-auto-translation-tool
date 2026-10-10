package org.universaltranslator.core.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import org.universaltranslator.core.UserAgent;

/** Bounded Java 8 HTTP client used to avoid shipping a large networking dependency. */
public final class HttpJsonClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public HttpJsonClient(int connectTimeoutMillis, int readTimeoutMillis) {
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
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
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }

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
            String response = stream == null ? "" : readBounded(stream);
            if (status < 200 || status >= 300) {
                String providerError = JsonStrings.readStringField(response, "error");
                if (providerError == null) {
                    providerError = JsonStrings.readStringField(response, "Message");
                }
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
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }
            byte[] body = (jsonBody == null ? "" : jsonBody).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream error = connection.getErrorStream();
                String response = error == null ? "" : readBounded(error);
                String providerError = JsonStrings.readStringField(response, "error");
                if (providerError == null) {
                    providerError = JsonStrings.readStringField(response, "Message");
                }
                throw new HttpStatusException(status, "Translation service returned HTTP " + status
                        + (providerError == null ? "" : ": " + providerError),
                        parseRetryAfterSeconds(connection.getHeaderField("Retry-After")));
            }
            InputStream stream = connection.getInputStream();
            StreamedResponse response = new StreamedResponse(
                    connection, status, connection.getContentType(),
                    stream == null ? new ByteArrayInputStream(new byte[0]) : stream);
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

    /** A response whose body is still open, so a server-sent event stream can be read as it arrives. */
    public static final class StreamedResponse implements Closeable {
        private final HttpURLConnection connection;
        private final int statusCode;
        private final String contentType;
        private final InputStream body;
        private boolean closed;

        private StreamedResponse(
                HttpURLConnection connection,
                int statusCode,
                String contentType,
                InputStream body
        ) {
            this.connection = connection;
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.body = body;
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

    private static String readBounded(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Translation response exceeded 1 MiB");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
