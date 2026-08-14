package org.universaltranslator.core.net;

import java.io.IOException;

/** HTTP failure with enough status information for bounded retry decisions. */
public final class HttpStatusException extends IOException {
    private final int statusCode;

    public HttpStatusException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }
}
