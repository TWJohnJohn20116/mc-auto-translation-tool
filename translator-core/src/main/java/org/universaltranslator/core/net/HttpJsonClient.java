package org.universaltranslator.core.net;

import java.io.ByteArrayOutputStream;
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

    public String request(
            String method,
            URI endpoint,
            String bodyText,
            String contentType,
            Map<String, String> headers
    ) throws IOException {
        String requestMethod = method == null ? "" : method.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"POST".equals(requestMethod) && !"PUT".equals(requestMethod)) {
            throw new IllegalArgumentException("Only POST and PUT translation requests are supported");
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
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", contentType == null || contentType.trim().isEmpty()
                    ? "application/json; charset=utf-8" : contentType.trim());
            connection.setRequestProperty("User-Agent", UserAgent.VALUE);
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }

            byte[] body = bodyValue.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
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
