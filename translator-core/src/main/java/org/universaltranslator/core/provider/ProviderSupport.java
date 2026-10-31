package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.JsonStrings;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class ProviderSupport {
    /**
     * Characters the accumulation has to grow by before a streaming listener is handed a new
     * snapshot.
     *
     * <p>Without a threshold every delta copied the whole accumulation and pushed a full string at
     * the UI, which is quadratic in the length of the answer. One constant, so the three streaming
     * providers throttle identically instead of keeping three copies of the same 32.
     */
    static final int PARTIAL_TEXT_STEP_CHARS = 32;

    private ProviderSupport() {
    }

    /**
     * The user message of one batch: a JSON object keyed by input line number.
     *
     * <p>Carrying the texts as JSON means a text that holds a line break, a quote or a backslash
     * travels as one unambiguous entry, so the model can answer every entry by key. Every batching
     * provider builds the same body from here, which is what keeps two of them from disagreeing
     * about the protocol.
     *
     * @param group texts of one batch, in order
     * @return the JSON object to send as the user message
     */
    static String batchUserBody(List<TranslationRequest> group) {
        StringBuilder user = new StringBuilder(256).append('{');
        for (int index = 0; index < group.size(); index++) {
            if (index > 0) {
                user.append(',');
            }
            user.append(JsonStrings.quote(Integer.toString(index + 1)))
                    .append(':').append(JsonStrings.quote(group.get(index).getText()));
        }
        return user.append('}').toString();
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
