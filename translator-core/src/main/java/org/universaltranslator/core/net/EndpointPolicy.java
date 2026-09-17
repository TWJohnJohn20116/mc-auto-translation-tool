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
        if ("http".equals(scheme) && isLocalOrPrivateNetwork(host)) {
            return uri;
        }
        throw new IllegalArgumentException("Remote translation endpoints must use HTTPS; HTTP is allowed only on loopback and private local networks");
    }

    private static boolean isLocalOrPrivateNetwork(String host) {
        if (isLoopbackLiteral(host)) {
            return true;
        }
        return isPrivateIpv4(host) || isPrivateIpv6(host);
    }

    private static boolean isLoopbackLiteral(String host) {
        return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    private static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int b0 = Integer.parseInt(parts[0]);
            int b1 = Integer.parseInt(parts[1]);
            int b2 = Integer.parseInt(parts[2]);
            int b3 = Integer.parseInt(parts[3]);
            if (b0 < 0 || b0 > 255 || b1 < 0 || b1 > 255 || b2 < 0 || b2 > 255 || b3 < 0 || b3 > 255) {
                return false;
            }
            if (b0 == 10) {
                return true;
            }
            if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                return true;
            }
            if (b0 == 192 && b1 == 168) {
                return true;
            }
            if (b0 == 169 && b1 == 254) {
                return true;
            }
        } catch (NumberFormatException exception) {
            return false;
        }
        return false;
    }

    private static boolean isPrivateIpv6(String host) {
        String clean = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return clean.startsWith("fc") || clean.startsWith("fd") || clean.startsWith("fe80");
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }
}
