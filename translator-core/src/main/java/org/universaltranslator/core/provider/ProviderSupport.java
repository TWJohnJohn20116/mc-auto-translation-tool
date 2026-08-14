package org.universaltranslator.core.provider;

import java.nio.charset.StandardCharsets;

final class ProviderSupport {
    private ProviderSupport() {
    }

    static String requireCredential(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required; store it only in the local config file");
        }
        return value.trim();
    }

    static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    static void requireUtf8Bytes(String name, String value, int maximumBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(name + " exceeds the provider's " + maximumBytes + " byte limit");
        }
    }
}
