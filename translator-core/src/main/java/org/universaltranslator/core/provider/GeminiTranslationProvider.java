package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationOutputValidator;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.TranslationStreamListener;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.HttpStatusException;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.net.ServerSentEvents;

import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Google Gemini's {@code generateContent} API.
 *
 * <p>The model is part of the request path ({@code /v1beta/models/{model}:generateContent}) and the
 * credential travels in {@code x-goog-api-key}. The system prompt is a first-class field
 * ({@code systemInstruction}) rather than a message, and the answer arrives as an array of
 * {@code candidates[0].content.parts[].text} fragments that have to be joined before validation.
 *
 * <p>Gemini can stream: {@code :streamGenerateContent?alt=sse} answers with one event per fragment.
 * Every case that would make streaming differ from the plain path falls back to it, so the feature
 * can only ever add an early preview.
 */
public final class GeminiTranslationProvider implements TranslationProvider {
    /** Base URL of the models collection; the model name and the method are appended to it. */
    public static final String DEFAULT_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models";
    private static final String GENERATE = ":generateContent";
    private static final String STREAM_GENERATE = ":streamGenerateContent";
    private static final int MAXIMUM_TOKENS_LIMIT = 2048;
    private static final int MINIMUM_COMPLETION_TOKENS = 512;
    /** The probe only has to prove the key and the model resolve, so it asks for one token. */
    private static final String PROBE_TEXT = "ping";

    private final String base;
    private final String apiKey;
    private final String model;
    private final TranslationPrompt.Settings prompt;
    private final HttpJsonClient http;
    /** Set once this endpoint proved it cannot answer with an event stream. */
    private volatile boolean streamingUnsupported;

    public GeminiTranslationProvider(String endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, new HttpJsonClient(5000, 120000),
                TranslationPrompt.Settings.standard());
    }

    public GeminiTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            TranslationPrompt.Settings prompt
    ) {
        this(endpoint, apiKey, model, new HttpJsonClient(5000, 120000), prompt);
    }

    GeminiTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt
    ) {
        this.base = stripMethod(endpoint == null || endpoint.trim().isEmpty()
                ? DEFAULT_ENDPOINT : endpoint.trim());
        // Validate at construction like the other providers, so a plaintext remote host is refused
        // before any credential could be sent rather than on the first translation.
        EndpointPolicy.requireSafeEndpoint(this.base);
        this.apiKey = ProviderSupport.requireCredential("Gemini API key", apiKey);
        this.model = requireModel(model);
        this.http = http;
        this.prompt = prompt == null ? TranslationPrompt.Settings.standard() : prompt;
    }

    /**
     * The provider id, extended with the prompt signature when the prompt is not the historical
     * one, exactly as the OpenAI-compatible provider does, so a quality mode or a custom prompt
     * gets its own cache entries.
     */
    @Override
    public String id() {
        String signature = prompt.signature();
        return signature.isEmpty() ? "gemini:" + model : "gemini:" + model + "#" + signature;
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        String text = request.getText();
        String response = post(text, TranslationPrompt.single(prompt, request),
                completionBudget(text), false);
        String translated = extractContent(response);
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("Gemini", response));
        }
        if (response.indexOf("\"usageMetadata\"") >= 0) {
            TranslationStats.global().recordUsage("gemini:" + model, response);
        }
        return TranslationOutputValidator.requireValid(text, translated);
    }

    @Override
    public String translateStreaming(TranslationRequest request, TranslationStreamListener listener)
            throws Exception {
        if (listener == null) {
            return translate(request);
        }
        if (streamingUnsupported || request.getText().indexOf('\n') >= 0) {
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        String text = request.getText();
        StringBuilder accumulated = new StringBuilder(64);
        boolean[] sawEvent = new boolean[1];
        try (HttpJsonClient.StreamedResponse response = http.postStreaming(
                URI.create(streamUrl()), requestBody(
                        text, TranslationPrompt.single(prompt, request),
                        completionBudget(text)), headers())) {
            if (!isEventStream(response.getContentType())) {
                streamingUnsupported = true;
                return TranslationProvider.super.translateStreaming(request, listener);
            }
            ServerSentEvents.read(response.getBody(), payload -> {
                String fragment = extractContent(payload);
                if (fragment != null && !fragment.isEmpty()) {
                    sawEvent[0] = true;
                    accumulated.append(fragment);
                    listener.onPartialText(accumulated.toString());
                }
                return true;
            });
        } catch (HttpStatusException rejected) {
            if (rejected.isRetryable()) {
                // A rate limit or a server error is transient, and retrying it is the resilient
                // provider's job; it must not be mistaken for "this endpoint cannot stream".
                throw rejected;
            }
            streamingUnsupported = true;
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        if (accumulated.length() == 0) {
            // Nothing usable came back, including the case of an event stream carrying only a
            // block reason. The plain path is the one that reports why, so it takes over.
            streamingUnsupported = !sawEvent[0];
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        return TranslationOutputValidator.requireValid(text, accumulated.toString());
    }

    /**
     * Sends a one-token generation through the configured model.
     *
     * @return the raw response body
     * @throws Exception when the key, the model or the response is unusable
     */
    public String probe() throws Exception {
        String response = post(PROBE_TEXT, "", 1, false);
        Object root = JsonStrings.parse(response);
        if (!(root instanceof Map) || !(((Map<?, ?>) root).get("candidates") instanceof java.util.List)) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("Gemini", response));
        }
        return response;
    }

    /** Joins every text fragment of the first candidate. */
    static String extractContent(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        return ProviderJson.joinField(response, "candidates[0].content.parts", "text");
    }

    /**
     * The catalog URL that belongs to this endpoint, so the settings screen can list models.
     *
     * <p>Accepts both the collection base the configuration ships with
     * ({@code .../v1beta/models}) and a full method URL pasted from the API reference, which is
     * truncated back to the collection first.
     *
     * @param endpoint configured generateContent endpoint
     * @return the {@code /models} collection URL
     */
    static String modelsUrl(String endpoint) {
        String base = stripMethod(endpoint == null || endpoint.trim().isEmpty()
                ? DEFAULT_ENDPOINT : endpoint.trim());
        int marker = base.lastIndexOf("/models/");
        if (marker >= 0) {
            base = base.substring(0, marker + "/models".length());
        }
        return base.endsWith("/models") ? base : base + "/models";
    }

    /**
     * Completion budget for one text. The same scaling the OpenAI-compatible provider uses, so a
     * long line is not truncated mid-sentence and then accepted as complete.
     */
    static int completionBudget(String text) {
        int requested = Math.min(text.length() * 2 + 32, MAXIMUM_TOKENS_LIMIT);
        return Math.max(MINIMUM_COMPLETION_TOKENS, requested);
    }

    private String post(String text, String system, int maximumTokens, boolean streaming)
            throws Exception {
        String url = streaming ? streamUrl() : generateUrl();
        EndpointPolicy.requireSafeEndpoint(url);
        return http.post(URI.create(url), requestBody(text, system, maximumTokens), headers());
    }

    private String generateUrl() {
        return base + "/" + model + GENERATE;
    }

    private String streamUrl() {
        return base + "/" + model + STREAM_GENERATE + "?alt=sse";
    }

    private Map<String, String> headers() {
        return Collections.singletonMap("x-goog-api-key", apiKey);
    }

    /** One {@code generateContent} request body. */
    private String requestBody(String text, String system, int maximumTokens) {
        StringBuilder body = new StringBuilder(text.length() + 320).append('{');
        if (system != null && !system.trim().isEmpty()) {
            body.append("\"systemInstruction\":{\"parts\":[{\"text\":")
                    .append(JsonStrings.quote(system)).append("]}],");
        }
        body.append("\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":")
                .append(JsonStrings.quote(text)).append("]}]")
                .append(",\"generationConfig\":{\"temperature\":0,\"maxOutputTokens\":")
                .append(maximumTokens).append('}');
        return body.append('}').toString();
    }

    /** A missing content type counts as "not streaming", as everywhere else in this package. */
    private static boolean isEventStream(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");
    }

    /** Drops a method suffix from a pasted URL so the model and the method can be appended. */
    private static String stripMethod(String endpoint) {
        String base = endpoint;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        int method = base.indexOf(GENERATE);
        if (method < 0) {
            method = base.indexOf(STREAM_GENERATE);
        }
        if (method >= 0) {
            base = base.substring(0, method);
        }
        int query = base.indexOf('?');
        return query < 0 ? base : base.substring(0, query);
    }

    private static String requireModel(String model) {
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("Gemini model is required");
        }
        return model.trim();
    }
}
