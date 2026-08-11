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
        int maximumTokens = Math.max(32, Math.min(512, request.getText().length() * 2 + 32));
        boolean offline = providerId.startsWith("offline-loopback");
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
        String translated = JsonStrings.readStringField(response, "content");
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException("OpenAI-compatible response did not contain translated content");
        }
        return TranslationOutputValidator.requireValid(request.getText(), translated);
    }

    private static String requireText(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
