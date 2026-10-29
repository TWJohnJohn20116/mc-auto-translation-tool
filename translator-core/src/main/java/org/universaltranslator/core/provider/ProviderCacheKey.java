package org.universaltranslator.core.provider;

import org.universaltranslator.core.net.CryptoSupport;

import java.net.URI;
import java.util.Locale;

/**
 * The identity every provider reports from {@link org.universaltranslator.core.TranslationProvider#id()}.
 *
 * <p>{@code TranslationCoordinator} builds both its in-flight key and the persistent-cache key from
 * that string, so it has to name every input that can change the answer. The provider name and the
 * model were not enough: {@code openai-compatible} reports a fixed provider name whatever
 * {@code llm-api-endpoint} holds, so pointing the same model at another host kept the old id and the
 * previous server's translation was replayed from the disk cache with no request ever being made.
 * The same hole existed for {@code gemini-endpoint}, {@code claude-endpoint} and
 * {@code azure-openai-endpoint} (whose {@code api-version} also decides the answer).
 *
 * <p>The endpoint therefore contributes a short digest of its scheme, host, port, path and query, and
 * each provider adds the request metadata of its own protocol through {@code extra} (the credential
 * header, {@code anthropic-version}, the Tencent action and version). The digest is computed once at
 * construction so {@code id()} stays allocation-cheap for the render thread.
 *
 * <p>Nothing secret enters the value: the digest is taken over the address only, credential-shaped
 * query values are replaced by a placeholder before hashing, and no header value is ever read. The
 * same configuration therefore hashes the same way, while rotating an API key does not change the
 * cache key and does not throw the cache away.
 */
final class ProviderCacheKey {
    /** Hex characters the digest keeps; 64 bits is far beyond the number of configurations in play. */
    private static final int FINGERPRINT_CHARS = 16;

    private ProviderCacheKey() {
    }

    /**
     * Builds the id of one provider instance.
     *
     * @param providerId fixed name of the provider family, such as {@code openai-compatible}
     * @param model model, deployment or engine id the request names
     * @param endpoint endpoint the request is sent to; {@code null} for a fixed vendor endpoint
     * @param extra protocol metadata that changes the answer, or {@code null}
     * @param promptSignature {@link org.universaltranslator.core.TranslationPrompt.Settings#signature()}
     * @return {@code providerId:model@digest[#promptSignature]}
     */
    static String of(
            String providerId,
            String model,
            URI endpoint,
            String extra,
            String promptSignature
    ) {
        StringBuilder key = new StringBuilder(64)
                .append(providerId).append(':').append(model).append('@')
                .append(fingerprint(endpoint, extra));
        if (promptSignature != null && !promptSignature.isEmpty()) {
            key.append('#').append(promptSignature);
        }
        return key.toString();
    }

    private static String fingerprint(URI endpoint, String extra) {
        String digest = CryptoSupport.sha256Hex(describe(endpoint, extra));
        return digest.substring(0, FINGERPRINT_CHARS);
    }

    private static String describe(URI endpoint, String extra) {
        StringBuilder text = new StringBuilder(96);
        if (endpoint == null) {
            text.append('-');
        } else {
            // getHost() never carries userinfo, so a credential pasted into the URL cannot leak in.
            text.append(nullToEmpty(endpoint.getScheme())).append("://")
                    .append(nullToEmpty(endpoint.getHost()));
            if (endpoint.getPort() >= 0) {
                text.append(':').append(endpoint.getPort());
            }
            text.append(nullToEmpty(endpoint.getRawPath()))
                    .append('\n').append(redactQuery(endpoint.getRawQuery()));
        }
        if (extra != null && !extra.isEmpty()) {
            text.append('\n').append(extra);
        }
        return text.toString();
    }

    /**
     * Keeps the shape of a query string while masking the values that carry a credential.
     *
     * <p>The query is part of the request, so a different one can answer differently and belongs in
     * the key; a {@code ?api-version=} value in particular decides which API answers. A value that is
     * named like a credential is the one thing that must not reach the key, because rotating it would
     * otherwise invalidate every cached translation.
     */
    private static String redactQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        StringBuilder masked = new StringBuilder(rawQuery.length());
        int cursor = 0;
        while (true) {
            int separator = rawQuery.indexOf('&', cursor);
            if (separator < 0) {
                separator = rawQuery.length();
            }
            if (masked.length() > 0) {
                masked.append('&');
            }
            String pair = rawQuery.substring(cursor, separator);
            int equals = pair.indexOf('=');
            if (equals < 0) {
                masked.append(pair);
            } else {
                String name = pair.substring(0, equals);
                masked.append(name).append('=');
                masked.append(isCredentialParameter(name) ? "{credential}" : pair.substring(equals + 1));
            }
            cursor = separator + 1;
            if (separator >= rawQuery.length()) {
                return masked.toString();
            }
        }
    }

    private static boolean isCredentialParameter(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("key") || lower.contains("token") || lower.contains("secret")
                || lower.contains("password") || lower.contains("credential")
                || lower.contains("signature") || lower.contains("sig");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
