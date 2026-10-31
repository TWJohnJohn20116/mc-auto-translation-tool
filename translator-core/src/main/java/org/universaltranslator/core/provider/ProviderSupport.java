package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationStreamListener;
import org.universaltranslator.core.net.HttpJsonClient;
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
     *
     * <p>The trade-off is part of the contract rather than a bug: an answer shorter than this never
     * produces an intermediate preview, only the closing one. That is what keeps a typical Minecraft
     * line -- six to twenty characters -- from repainting the HUD twenty times.
     */
    static final int PARTIAL_TEXT_STEP_CHARS = 32;

    /**
     * Largest progress budget one event stream is given, in milliseconds.
     *
     * <p>The budget follows the remaining {@code api-read-timeout-ms} window, which is what the user
     * configured; the ceiling only stops a genuinely stuck endpoint from holding a translation
     * worker for the whole four-times-default window. Two of the three streaming providers kept
     * their own copy of this ceiling and the third kept none, so one setting produced two different
     * stall limits — twenty minutes on the OpenAI-compatible path against five minutes elsewhere.
     */
    static final long STREAM_PROGRESS_BUDGET_CEILING_MILLIS = 300000L;

    private ProviderSupport() {
    }

    /**
     * How long one streamed response may deliver nothing before its watchdog fails the read.
     *
     * <p>The single decision point for the stall limit of every streaming provider, so raising
     * {@code api-read-timeout-ms} can never mean a different thing per provider.
     *
     * @param response the stream about to be read
     * @return the remaining window of this response, capped at the ceiling above
     */
    static long stallBudgetMillis(HttpJsonClient.StreamedResponse response) {
        return Math.min(STREAM_PROGRESS_BUDGET_CEILING_MILLIS, response.getProgressBudgetMillis());
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

    /**
     * Reads one batch reply with every fallback the protocol has.
     *
     * <p>The JSON object is tried first, then the object a relay answers when it ignores the
     * prefilled opening brace, then the numbered list a small model writes instead of JSON. Two of
     * the three batching providers only made the first attempt, so one stray brace or one model that
     * answered "1. …" threw away a whole batch reply and paid for a request per line afterwards —
     * more requests than batching was introduced to save.
     *
     * @param content assistant message text of the reply
     * @param expected number of texts the batch carried
     * @param prefilled whether the request ended with a prefilled opening brace that has to be put
     *                  back in front of {@code content} before it is parsed
     * @return one entry per expected line, {@code null} where the reply had nothing usable, or
     *         {@code null} when the reply carries no batch of any shape at all
     */
    static List<String> parseBatchReply(String content, int expected, boolean prefilled) {
        List<String> parsed = prefilled && content != null
                ? OpenAiChatTranslationProvider.parseJsonBatch('{' + content, expected) : null;
        if (parsed == null) {
            parsed = OpenAiChatTranslationProvider.parseJsonBatch(content, expected);
        }
        if (parsed == null) {
            parsed = OpenAiChatTranslationProvider.parseNumberedBatch(content, expected);
        }
        return parsed;
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

    /**
     * The single place the three streaming providers publish previews from.
     *
     * <p>Each of them carried its own copy of "accumulate, publish once per
     * {@link #PARTIAL_TEXT_STEP_CHARS}, publish the tail when the answer ends", and only the
     * OpenAI-compatible copy was ever executed by a test: Claude's and Gemini's success paths had no
     * coverage at all. A copy whose closing test drifted -- {@code >} instead of {@code !=}, or a
     * throttle that never fires -- leaves the preview stuck at the last step or drops the complete
     * answer, and the build stays green. Publishing through one class is what keeps the three the
     * same behaviour instead of three behaviours that happen to look alike.
     */
    static final class PreviewPublisher {
        private final TranslationStreamListener listener;
        /** Characters already handed to the listener; the closing publish is driven by it. */
        private int published;

        PreviewPublisher(TranslationStreamListener listener) {
            this.listener = listener;
        }

        /**
         * Publishes the accumulation once it has grown by {@link #PARTIAL_TEXT_STEP_CHARS}.
         *
         * @param accumulated text produced so far
         */
        void append(CharSequence accumulated) {
            if (accumulated.length() - published < PARTIAL_TEXT_STEP_CHARS) {
                return;
            }
            published = accumulated.length();
            listener.onPartialText(accumulated.toString());
        }

        /**
         * Publishes the answer the throttle held back, so the last preview is the whole translation.
         *
         * @param accumulated the complete answer
         */
        void finish(CharSequence accumulated) {
            if (published == accumulated.length()) {
                return;
            }
            published = accumulated.length();
            listener.onPartialText(accumulated.toString());
        }
    }
}
