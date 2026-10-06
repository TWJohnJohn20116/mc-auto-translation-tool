package org.universaltranslator.core.net;

import java.io.IOException;

/** HTTP failure with enough status information for bounded retry decisions. */
public final class HttpStatusException extends IOException {
    private final int statusCode;
    private final Long retryAfterSeconds;

    public HttpStatusException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    /**
     * Creates a failure that may carry the retry delay advertised by the server.
     *
     * @param retryAfterSeconds value of the {@code Retry-After} header in seconds when the server
     *                          sent the delta-seconds form, or {@code null} when the header was
     *                          absent or not usable
     */
    public HttpStatusException(int statusCode, String message, Long retryAfterSeconds) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the delay requested by the server through {@code Retry-After}, in seconds.
     *
     * <p>Only the delta-seconds form is supported; the HTTP-date form is always treated as unknown
     * and reported as {@code null}, as is a missing or malformed header.
     *
     * @return the advertised delay in seconds, or {@code null} when unknown
     */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isRetryable() {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }
}
