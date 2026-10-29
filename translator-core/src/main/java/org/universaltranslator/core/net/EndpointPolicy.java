package org.universaltranslator.core.net;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Translation endpoint must not embed credentials");
        }
        String host = lower(uri.getHost());
        if (host == null) {
            // getHost() returns null for two very different endpoints: one without a host at all,
            // and one whose host the JDK could not represent (an internationalised name such as
            // http://群晖.local:5000, or an underscore the strict parser rejects). Only the first
            // is the user's mistake, so recover the authority before deciding.
            host = lower(recoverHost(uri));
            uri = withAsciiHost(uri, host);
        }
        if ("https".equals(scheme)) {
            return uri;
        }
        if ("http".equals(scheme) && isLocalOrPrivateNetwork(host)) {
            return uri;
        }
        throw new IllegalArgumentException("Remote translation endpoints must use HTTPS; HTTP is allowed only on loopback and private local networks");
    }

    /**
     * Recovers the host from the raw authority when {@link URI#getHost()} refused to parse it.
     *
     * <p>An internationalised name is converted with {@link java.net.IDN}; a plain ASCII name that
     * the strict parser rejected (an underscore is the common case) is accepted unchanged. The two
     * failures stay distinguishable so the diagnostics can say which one happened instead of
     * claiming the endpoint has no host at all.
     *
     * @throws IllegalArgumentException when there is no authority, or the name cannot be expressed
     *         in ASCII at all
     */
    private static String recoverHost(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            throw new IllegalArgumentException("Translation endpoint must contain a host");
        }
        int at = authority.lastIndexOf('@');
        String hostPort = at < 0 ? authority : authority.substring(at + 1);
        String host;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Translation endpoint must contain a host");
            }
            host = hostPort.substring(0, close + 1);
        } else {
            int colon = hostPort.lastIndexOf(':');
            host = colon > 0 && isDecimal(hostPort.substring(colon + 1))
                    ? hostPort.substring(0, colon) : hostPort;
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Translation endpoint must contain a host");
        }
        if (isAscii(host)) {
            return host;
        }
        try {
            return java.net.IDN.toASCII(host, java.net.IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException unrepresentable) {
            throw new IllegalArgumentException(
                    "Translation endpoint host contains characters that cannot be written in "
                            + "ASCII; use an ASCII host name or an IP address", unrepresentable);
        }
    }

    /** Rebuilds the endpoint with the recovered host so the caller receives a usable URI. */
    private static URI withAsciiHost(URI uri, String host) {
        try {
            // The multi-argument constructor re-brackets an IPv6 literal itself, so the host is
            // passed exactly as recovered.
            return new URI(uri.getScheme(), uri.getUserInfo(), host, uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("Translation endpoint is not a valid URI", invalid);
        }
    }

    private static boolean isAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 126) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDecimal(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
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
        // Parsed by hand instead of split() + matches(): both compile a Pattern on every call, and
        // this runs again for every request an HTTP endpoint makes.
        int[] octets = new int[4];
        int count = 0;
        int index = 0;
        while (count < 4) {
            int start = index;
            int value = 0;
            while (index < host.length() && host.charAt(index) != '.') {
                char character = host.charAt(index);
                // Only plain decimal octets are accepted; this rejects "+10", "-10", " 10", "0x0a", "1e2" ...
                if (character < '0' || character > '9' || index - start >= 3) {
                    return false;
                }
                value = value * 10 + (character - '0');
                index++;
            }
            if (index == start) {
                return false;
            }
            octets[count++] = value;
            if (index >= host.length()) {
                break;
            }
            index++;
            if (index >= host.length()) {
                // A trailing dot leaves an empty final octet, which is not a dotted quad.
                return false;
            }
        }
        if (count != 4 || index != host.length()) {
            return false;
        }
        int b0 = octets[0];
        int b1 = octets[1];
        int b2 = octets[2];
        int b3 = octets[3];
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
            // The link-local block as a whole, not one /32 inside it. Cloud credential
            // endpoints live all over it (169.254.169.254 IMDS, 169.254.170.2 ECS task role,
            // 169.254.170.23 ECS IPv4, 169.254.169.253 VPC DNS, 169.254.169.123 time sync),
            // and blocking only the first one left every other door open to a plaintext
            // request that carries the API key and the source text. A link-local host that
            // genuinely serves HTTP is rare enough that refusing the block is the right
            // default; loopback or a reverse proxy covers those setups.
            return false;
        }
        return false;
    }

    /**
     * True for the IPv6 metadata endpoint that sits inside the unique-local range.
     *
     * <p>{@code fd00:ec2::254} is the AWS instance metadata service reached over IPv6. It is a
     * unique-local address, so the fc00::/7 branch above would otherwise wave it through and the
     * provider's credentials would travel in plaintext to the one address that hands out
     * credentials. The range is compared group by group after expanding {@code ::}.
     */
    private static boolean isIpv6CloudMetadata(String host) {
        int[] groups = expandIpv6(host);
        if (groups == null) {
            return false;
        }
        return groups[0] == 0xfd00 && groups[1] == 0x0ec2
                && groups[2] == 0 && groups[3] == 0 && groups[4] == 0 && groups[5] == 0
                && groups[6] == 0 && groups[7] == 0x0254;
    }

    /** Expands an IPv6 literal into its eight 16-bit groups, or returns null when it is not one. */
    private static int[] expandIpv6(String host) {
        String source = host;
        int zone = source.indexOf('%');
        if (zone >= 0) {
            // A scope suffix ("fe80::1%eth0") is not part of the address.
            source = source.substring(0, zone);
        }
        int compressed = source.indexOf("::");
        String[] head = (compressed < 0 ? source : source.substring(0, compressed)).split(":", -1);
        String[] tail = compressed < 0
                ? new String[0] : source.substring(compressed + 2).split(":", -1);
        List<Integer> values = new ArrayList<Integer>();
        if (!appendGroups(head, values)) {
            return null;
        }
        int headCount = values.size();
        if (compressed >= 0) {
            List<Integer> rest = new ArrayList<Integer>();
            if (!appendGroups(tail, rest) || headCount + rest.size() > 7) {
                return null;
            }
            while (values.size() + rest.size() < 8) {
                values.add(Integer.valueOf(0));
            }
            values.addAll(rest);
        }
        return values.size() == 8 ? toArray(values) : null;
    }

    private static boolean appendGroups(String[] parts, List<Integer> values) {
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!part.matches("[0-9a-fA-F]{1,4}")) {
                return false;
            }
            values.add(Integer.valueOf(Integer.parseInt(part, 16)));
        }
        return true;
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index).intValue();
        }
        return result;
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
        // fc00::/7 unique local addresses (first byte 0xfc or 0xfd), minus the IPv6 metadata
        // endpoint that sits inside that range.
        if ((group & 0xfe00) == 0xfc00) {
            return !isIpv6CloudMetadata(host);
        }
        // fe80::/10 link-local addresses.
        return (group & 0xffc0) == 0xfe80;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }
}
