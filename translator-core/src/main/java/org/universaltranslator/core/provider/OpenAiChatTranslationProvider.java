package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationOutputValidator;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.HttpStatusException;
import org.universaltranslator.core.net.JsonStrings;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small OpenAI-compatible chat provider used by local llama.cpp and optional hosted APIs. */
public final class OpenAiChatTranslationProvider implements TranslationProvider {
    /** Matches the loopback llama.cpp server's {@code --ctx-size} argument. */
    private static final int OFFLINE_CONTEXT_TOKENS = 1024;
    /** System prompt plus chat-template tokens charged against the offline context. */
    private static final int OFFLINE_PROMPT_OVERHEAD_TOKENS = 128;
    /** Upper bound for the completion budget of a hosted OpenAI-compatible endpoint. */
    private static final int MAXIMUM_TOKENS_LIMIT = 2048;
    /** Smallest completion budget a hosted endpoint gets, so a reasoning model can finish. */
    private static final int MINIMUM_COMPLETION_TOKENS = 512;
    /**
     * Completion budget for a model that spends its budget thinking before it writes the answer.
     * The 2048 ceiling is enough for a plain model, but a thinking model can exhaust it and return
     * only {@code reasoning_content} — which is exactly what "returned only reasoning content"
     * reports. Raising the bound costs nothing for a model that stops early, because
     * {@code max_tokens} is an upper bound rather than a reservation.
     */
    private static final int REASONING_COMPLETION_TOKENS = 8192;

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final String providerId;
    private final HttpJsonClient http;
    /**
     * Set once a response arrives carrying reasoning content but no answer. Every later request
     * then starts with {@link #REASONING_COMPLETION_TOKENS}: without this the provider paid for
     * two round trips on every single line of a thinking model, which halved throughput on exactly
     * the endpoints that are slowest already.
     */
    private volatile boolean reasoningModel;
    /**
     * Largest completion budget this endpoint has accepted. Lowered when the endpoint rejects a
     * value, so an endpoint with a smaller output cap is only charged one rejected request.
     */
    private volatile int acceptedReasoningTokens = REASONING_COMPLETION_TOKENS;

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
        this.endpoint = EndpointPolicy.requireSafeEndpoint(normalizeEndpoint(endpoint));
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = requireText("model", model);
        this.providerId = requireText("providerId", providerId);
        this.http = http;
    }

    /**
     * Accepts the base-URL form OpenAI-compatible relays advertise.
     *
     * <p>{@code https://host/v1} is what those services print as their API address, and posting a
     * chat request to it answers with the relay's HTML page while {@code /v1/models} still works —
     * a service that looks healthy and never translates. Only that exact trailing form is
     * rewritten; custom paths such as Azure deployments with an api-version query pass through.
     *
     * @param endpoint configured chat-completions endpoint
     * @return the endpoint to request, with the chat path filled in when only a base URL was given
     */
    static String normalizeEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.endsWith("/v1")) {
            return value + "/chat/completions";
        }
        if (value.endsWith("/v1/")) {
            return value + "chat/completions";
        }
        return value;
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
        int requestedTokens = Math.min(inputLength * 2 + 32, MAXIMUM_TOKENS_LIMIT);
        int maximumTokens;
        if (offline) {
            // The loopback llama.cpp server runs --ctx-size 1024 and shares that window
            // between prompt and completion. Cap the completion to what is left after a
            // conservative prompt estimate, with no floor that could overflow the context.
            int remaining = OFFLINE_CONTEXT_TOKENS - OFFLINE_PROMPT_OVERHEAD_TOKENS
                    - estimatePromptTokens(request.getText());
            maximumTokens = Math.max(1, Math.min(Math.max(64, requestedTokens), remaining));
        } else if (reasoningModel) {
            // This endpoint already proved it thinks before it answers, so start with the larger
            // budget instead of spending a round trip rediscovering that on every single line.
            maximumTokens = Math.max(MINIMUM_COMPLETION_TOKENS,
                    Math.min(REASONING_COMPLETION_TOKENS, acceptedReasoningTokens));
        } else {
            // Reasoning models spend completion tokens thinking before they write the answer, so
            // even a short input must leave room to finish. max_tokens is an upper bound rather
            // than a reservation, so this costs nothing for models that stop early.
            maximumTokens = Math.max(MINIMUM_COMPLETION_TOKENS, requestedTokens);
        }
        String response = post(system, request.getText(), maximumTokens, offline);
        String translated = extractContent(response);
        if ((translated == null || translated.trim().isEmpty()) && !offline
                && JsonStrings.readStringField(response, "reasoning_content") != null) {
            // The model thought until the budget ran out and never wrote the translation; the
            // answer only appears once the completion is allowed to finish. Remember the endpoint
            // reasons, so the next line does not repeat the smaller first attempt.
            reasoningModel = true;
            int reasoningBudget = Math.max(MINIMUM_COMPLETION_TOKENS,
                    Math.min(REASONING_COMPLETION_TOKENS, acceptedReasoningTokens));
            if (reasoningBudget > maximumTokens) {
                response = postWithAcceptedBudget(
                        system, request.getText(), reasoningBudget, maximumTokens, offline);
                translated = extractContent(response);
            }
        }
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException(describeMissingContent(response));
        }
        return TranslationOutputValidator.requireValid(request.getText(), translated);
    }

    /** Posts one chat completion and returns the raw response body. */
    private String post(String system, String text, int maximumTokens, boolean offline) throws Exception {
        String body = new StringBuilder(text.length() + 320)
                .append('{')
                .append("\"model\":").append(JsonStrings.quote(model)).append(',')
                .append("\"messages\":[")
                .append("{\"role\":\"system\",\"content\":").append(JsonStrings.quote(system)).append("},")
                .append("{\"role\":\"user\",\"content\":").append(JsonStrings.quote(text)).append("}],")
                .append("\"temperature\":0,\"max_tokens\":").append(maximumTokens).append(',')
                .append(offline ? "\"repeat_penalty\":1.12," : "")
                .append("\"stream\":false}")
                .toString();
        String authorization = apiKey.isEmpty() ? null : "Bearer " + apiKey;
        return http.post(endpoint, body, authorization);
    }

    /**
     * Posts with a larger completion budget and falls back to the previous one when the endpoint
     * rejects the larger value. Endpoints differ in how much output they allow; without the
     * fallback a rejection would fail the line outright, even though the smaller budget did return
     * a response. The rejected value is remembered so the same endpoint is charged for it once.
     */
    private String postWithAcceptedBudget(String system, String text, int budget, int fallbackBudget,
            boolean offline) throws Exception {
        try {
            return post(system, text, budget, offline);
        } catch (HttpStatusException rejected) {
            if (!rejectsCompletionBudget(rejected)) {
                throw rejected;
            }
            acceptedReasoningTokens = fallbackBudget;
            return post(system, text, fallbackBudget, offline);
        }
    }

    /**
     * Whether a failure looks like "the completion budget you asked for is not allowed". Only
     * client errors whose message names the token limit qualify, so an authentication or quota
     * failure is never mistaken for a budget problem and silently retried with a smaller budget.
     */
    private static boolean rejectsCompletionBudget(HttpStatusException failure) {
        if (failure.getStatusCode() < 400 || failure.getStatusCode() >= 500) {
            return false;
        }
        String message = failure.getMessage() == null
                ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("max_tokens") || message.contains("max_completion_tokens")
                || message.contains("max output") || message.contains("too large")
                || message.contains("too long") || message.contains("exceed");
    }

    /**
     * Explains a response that carried no translation.
     *
     * <p>Relays answer HTTP 200 with their own JSON error object when the token is out of quota,
     * the model name is unknown, or the request is rejected. Reporting only "no translated
     * content" hides the one sentence that tells the user what to fix.
     *
     * @param response raw response body
     * @return the provider's own message when it sent one, otherwise a bounded body excerpt
     */
    static String describeMissingContent(String response) {
        String providerError = JsonStrings.readStringField(response, "message");
        if (providerError == null) {
            providerError = JsonStrings.readStringField(response, "error");
        }
        if (providerError != null && !providerError.trim().isEmpty()) {
            return "OpenAI-compatible endpoint reported: " + providerError.trim();
        }
        if (JsonStrings.readStringField(response, "reasoning_content") != null) {
            return "OpenAI-compatible model returned only reasoning content; "
                    + "pick a non-reasoning model or raise the completion budget";
        }
        return "OpenAI-compatible response did not contain translated content"
                + JsonStrings.bodyPreview(response);
    }

    /**
     * Sends a one-token completion through the configured endpoint.
     *
     * <p>The settings screen uses this for its connection test. Reading {@code /models} is not
     * enough: a base URL that lacks the chat path still answers a catalog request while every
     * translation fails, so the test would report a healthy service that never works.
     *
     * @return the raw response body
     * @throws Exception when the endpoint, credential, model, or response is unusable
     */
    public String probe() throws Exception {
        String body = new StringBuilder(192)
                .append('{')
                .append("\"model\":").append(JsonStrings.quote(model)).append(',')
                .append("\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],")
                .append("\"temperature\":0,\"max_tokens\":1,\"stream\":false}")
                .toString();
        String authorization = apiKey.isEmpty() ? null : "Bearer " + apiKey;
        String response = http.post(endpoint, body, authorization);
        Object root = JsonStrings.parse(response);
        // A relay that answers 200 with its own error object would otherwise look healthy here,
        // and the user would only find out when every translation failed.
        if (!(root instanceof Map) || !(((Map<?, ?>) root).get("choices") instanceof List)) {
            throw new IllegalStateException(describeMissingContent(response));
        }
        return response;
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
