package org.universaltranslator.core.net;

import java.net.URI;

/** Prevents accidental plaintext transmission to non-local translation services. */
public final class EndpointPolicy {
    private EndpointPolicy() {
    }

    public static URI requireSafeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("Translation endpoint is required");
        }

        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Translation endpoint is not a valid URI", exception);
        }

        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        if (host == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Translation endpoint must contain a host and no embedded credentials");
        }
        if ("https".equals(scheme)) {
            return uri;
        }
        if ("http".equals(scheme) && isLoopbackLiteral(host)) {
            return uri;
        }
        throw new IllegalArgumentException("Remote translation endpoints must use HTTPS; HTTP is allowed only on loopback");
    }

    private static boolean isLoopbackLiteral(String host) {
        return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }
}
