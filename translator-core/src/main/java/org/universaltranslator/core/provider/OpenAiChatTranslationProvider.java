package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationOutputValidator;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;

import java.net.URI;

/** Small OpenAI-compatible chat provider used by local llama.cpp and optional hosted APIs. */
public final class OpenAiChatTranslationProvider implements TranslationProvider {
    /** Matches the loopback llama.cpp server's {@code --ctx-size} argument. */
    private static final int OFFLINE_CONTEXT_TOKENS = 1024;
    /** System prompt plus chat-template tokens charged against the offline context. */
    private static final int OFFLINE_PROMPT_OVERHEAD_TOKENS = 128;
    /** Upper bound for the completion budget of a hosted OpenAI-compatible endpoint. */
    private static final int MAXIMUM_TOKENS_LIMIT = 2048;

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final String providerId;
    private final HttpJsonClient http;

    public OpenAiChatTranslationProvider(String endpoint, String apiKey, String model, String providerId) {
        this(endpoint, apiKey, model, providerId, new HttpJsonClient(5000, 120000));
    }

    OpenAiChatTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            String providerId,
            HttpJsonClient http
    ) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = requireText("model", model);
        this.providerId = requireText("providerId", providerId);
        this.http = http;
    }

    @Override
    public String id() {
        return providerId + ":" + model;
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        String target = TargetLanguage.translationInstruction(request.getTargetLanguage());
        String system = "You are a professional Minecraft game-localization translator. "
                + "Translate the user text to " + target
                + ". Reply with only the translation, without quotes, labels, notes, or explanations. "
                + "Preserve punctuation, whitespace, URLs, usernames, placeholders, and Minecraft formatting markers."
                + (request.getText().indexOf('\n') >= 0
                ? " Keep exactly the same number and order of lines." : "");
        boolean offline = providerId.startsWith("offline-loopback");
        // Budget the completion from the input length. A fixed 512-token cap truncated
        // long lines mid-sentence, and the clipped result was still short enough to pass
        // TranslationOutputValidator, so it entered the persistent cache.
        int inputLength = request.getText().length();
        int maximumTokens = Math.max(64,
                Math.min(inputLength * 2 + 32, MAXIMUM_TOKENS_LIMIT));
        if (offline) {
            // The loopback llama.cpp server runs --ctx-size 1024 and shares that window
            // between prompt and completion. Cap the completion to what is left after a
            // conservative prompt estimate, with no floor that could overflow the context.
            int remaining = OFFLINE_CONTEXT_TOKENS - OFFLINE_PROMPT_OVERHEAD_TOKENS
                    - estimatePromptTokens(request.getText());
            maximumTokens = Math.max(1, Math.min(maximumTokens, remaining));
        }
        String body = new StringBuilder(request.getText().length() + 320)
                .append('{')
                .append("\"model\":").append(JsonStrings.quote(model)).append(',')
                .append("\"messages\":[")
                .append("{\"role\":\"system\",\"content\":").append(JsonStrings.quote(system)).append("},")
                .append("{\"role\":\"user\",\"content\":")
                .append(JsonStrings.quote(request.getText())).append("}],")
                .append("\"temperature\":0,\"max_tokens\":").append(maximumTokens).append(',')
                .append(offline ? "\"repeat_penalty\":1.12," : "")
                .append("\"stream\":false}")
                .toString();
        String authorization = apiKey.isEmpty() ? null : "Bearer " + apiKey;
        String response = http.post(endpoint, body, authorization);
        String translated = extractContent(response);
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException("OpenAI-compatible response did not contain translated content");
        }
        return TranslationOutputValidator.requireValid(request.getText(), translated);
    }

    static String extractContent(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        String translated = JsonStrings.readStringPath(response, "choices[0].message.content");
        if (translated != null && !translated.trim().isEmpty()) {
            return translated;
        }
        return JsonStrings.readStringField(response, "content");
    }

    /**
     * Conservative prompt-token estimate for the offline context. ASCII text averages about
     * one token per four characters while non-ASCII text such as Chinese tokenizes near one
     * token per character, so both are rounded up to avoid overflowing the context window.
     */
    static int estimatePromptTokens(String text) {
        int ascii = 0;
        int nonAscii = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) < 0x80) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        return (ascii + 3) / 4 + nonAscii + 1;
    }

    private static String requireText(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
