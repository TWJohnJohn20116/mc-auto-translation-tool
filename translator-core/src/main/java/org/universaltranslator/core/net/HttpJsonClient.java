package org.universaltranslator.core.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

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
        try {
            connection.setRequestMethod(requestMethod);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", contentType == null || contentType.trim().isEmpty()
                    ? "application/json; charset=utf-8" : contentType.trim());
            connection.setRequestProperty("User-Agent", "MCAutoTranslationTool/1.3.1");
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
                        + (providerError == null ? "" : ": " + providerError));
            }
            return response;
        } finally {
            connection.disconnect();
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
