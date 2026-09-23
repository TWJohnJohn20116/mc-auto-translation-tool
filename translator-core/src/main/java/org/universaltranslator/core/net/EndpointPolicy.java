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
        // URI.getHost() keeps the brackets around IPv6 literals (e.g. "[fd00::1]").
        String literal = stripBrackets(host);
        if (isLoopbackLiteral(literal)) {
            return true;
        }
        return isPrivateIpv4(literal) || isPrivateIpv6(literal);
    }

    private static String stripBrackets(String host) {
        return host.length() > 2 && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean isLoopbackLiteral(String host) {
        return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    private static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (int index = 0; index < parts.length; index++) {
            // Only plain decimal octets are accepted; this rejects "+10", "-10", " 10", "0x0a", "1e2" ...
            if (!parts[index].matches("[0-9]{1,3}")) {
                return false;
            }
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
                // 169.254.0.0/16 stays allowed for link-local LAN services, but the cloud
                // metadata address (and the whole /32 it sits in) must never be plaintext.
                return !isCloudMetadataAddress(b0, b1, b2, b3);
            }
        } catch (NumberFormatException exception) {
            return false;
        }
        return false;
    }

    private static boolean isCloudMetadataAddress(int b0, int b1, int b2, int b3) {
        return b0 == 169 && b1 == 254 && b2 == 169 && b3 == 254;
    }

    private static boolean isPrivateIpv6(String host) {
        if (host.indexOf(':') < 0) {
            // Not an IPv6 literal; a hostname such as "fda.gov" or "fdroid.org" must not match.
            return false;
        }
        String firstGroup = host.startsWith(":") ? "" : host.substring(0, host.indexOf(':'));
        if (firstGroup.isEmpty()) {
            // Compressed form starting with "::" (e.g. "::1"); no fc00::/7 or fe80::/10 prefix.
            return false;
        }
        if (!firstGroup.matches("[0-9a-fA-F]{1,4}")) {
            return false;
        }
        int group;
        try {
            group = Integer.parseInt(firstGroup, 16);
        } catch (NumberFormatException exception) {
            return false;
        }
        // fc00::/7 unique local addresses (first byte 0xfc or 0xfd).
        if ((group & 0xfe00) == 0xfc00) {
            return true;
        }
        // fe80::/10 link-local addresses.
        return (group & 0xffc0) == 0xfe80;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }
}
