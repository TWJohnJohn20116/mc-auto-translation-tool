package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationOutputValidator;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TranslationStreamListener;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.HttpStatusException;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.net.ServerSentEvents;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Anthropic's Messages API.
 *
 * <p>Three things differ from the OpenAI-compatible shape and are why this is a provider of its
 * own: the credential is {@code x-api-key} rather than a bearer token, {@code anthropic-version} is
 * mandatory, and the system prompt is a top-level {@code system} field rather than a message with
 * {@code role: "system"}. The answer is an array of {@code content[].text} fragments.
 *
 * <p>Streaming uses the same {@code "stream": true} flag as OpenAI, but the event payloads are
 * Anthropic's own: only {@code content_block_delta.delta.text} is translation.
 */
public final class ClaudeTranslationProvider implements TranslationProvider {
    /** Pinned API version; Anthropic requires the header on every request. */
    public static final String API_VERSION = "2023-06-01";
    public static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String MODELS_SUFFIX = "/models";
    private static final String MESSAGES_SUFFIX = "/messages";
    private static final int MAXIMUM_TOKENS_LIMIT = 4096;
    private static final int MINIMUM_COMPLETION_TOKENS = 512;
    /**
     * Field that reports why the model stopped, on a plain reply and inside a
     * {@code message_delta} stream event alike. Without it a reply cut off at {@code max_tokens}
     * looks exactly like a short translation.
     */
    private static final String STOP_REASON_FIELD = "stop_reason";
    /** The only {@code stop_reason} that means the model finished its answer by itself. */
    private static final String STOP_REASON_END_TURN = "end_turn";
    /** {@code stop_reason} of an answer cut off at {@code max_tokens}. */
    private static final String STOP_REASON_MAX_TOKENS = "max_tokens";
    private static final String PROBE_TEXT = "ping";

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final TranslationPrompt.Settings prompt;
    private final HttpJsonClient http;
    /** Set once this endpoint proved it cannot answer with an event stream. */
    private volatile boolean streamingUnsupported;

    public ClaudeTranslationProvider(String endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, new HttpJsonClient(5000, 120000),
                TranslationPrompt.Settings.standard());
    }

    public ClaudeTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            TranslationPrompt.Settings prompt
    ) {
        this(endpoint, apiKey, model, new HttpJsonClient(5000, 120000), prompt);
    }

    ClaudeTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt
    ) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(
                endpoint == null || endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint);
        this.apiKey = ProviderSupport.requireCredential("Claude API key", apiKey);
        this.model = requireModel(model);
        this.http = http;
        this.prompt = prompt == null ? TranslationPrompt.Settings.standard() : prompt;
    }

    /** The provider id, extended with the prompt signature when the prompt is not the default. */
    @Override
    public String id() {
        String signature = prompt.signature();
        return signature.isEmpty() ? "claude:" + model : "claude:" + model + "#" + signature;
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        String text = request.getText();
        DebugLog.global().logRequest("claude:" + model, model, endpoint.toString(),
                request.getKind().name(), text);
        int budget = completionBudget(text);
        String response = post(text, TranslationPrompt.single(prompt, request), budget, false);
        String translated = extractContent(response);
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("Claude", response));
        }
        // Completeness is only worth asking about once there is content to judge: an empty answer is
        // explained far better by the provider's own message and the body excerpt than by a stop
        // reason, while a cut-off answer is distinguishable from a short one only here.
        requireCompleteAnswer(JsonStrings.readStringField(response, STOP_REASON_FIELD), budget);
        if (response.indexOf("\"usage\"") >= 0) {
            TranslationStats.global().recordUsage("claude:" + model, response);
        }
        DebugLog.global().logResponse("claude:" + model, request.getKind().name(), translated);
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
        int budget = completionBudget(text);
        StringBuilder accumulated = new StringBuilder(64);
        boolean[] sawEvent = new boolean[1];
        // A stream reports how generation ended in its message_delta event, and message_stop is its
        // own statement that the answer is complete; both have to be captured while the body is
        // still being read. They are separate because only the latter proves the stream ended: a
        // connection that died mid-answer never sends it.
        String[] stopReason = new String[1];
        boolean[] sawEnd = new boolean[1];
        try (HttpJsonClient.StreamedResponse response = http.postStreaming(endpoint, requestBody(
                text, TranslationPrompt.single(prompt, request), budget, true),
                headers())) {
            if (!isEventStream(response.getContentType())) {
                streamingUnsupported = true;
                return TranslationProvider.super.translateStreaming(request, listener);
            }
            ServerSentEvents.read(response.getBody(), payload -> {
                if ("message_stop".equals(JsonStrings.readStringField(payload, "type"))) {
                    sawEnd[0] = true;
                    DebugLog.global().logStreamEvent("claude:" + model, "end", 0);
                    return false;
                }
                String reported = JsonStrings.readStringField(payload, STOP_REASON_FIELD);
                if (reported != null && !STOP_REASON_END_TURN.equals(reported)) {
                    stopReason[0] = reported;
                }
                String fragment = JsonStrings.readStringPath(payload, "delta.text");
                if (fragment == null) {
                    fragment = JsonStrings.readStringPath(payload, "content_block.text");
                }
                if (fragment != null && !fragment.isEmpty()) {
                    sawEvent[0] = true;
                    accumulated.append(fragment);
                    listener.onPartialText(accumulated.toString());
                } else if (JsonStrings.readStringField(payload, "error") != null) {
                    // Anthropic reports a failure as an ordinary event behind HTTP 200; stop and let
                    // the plain path produce the message the user has always seen.
                    return false;
                }
                return true;
            });
        } catch (HttpStatusException rejected) {
            if (rejected.isRetryable()) {
                throw rejected;
            }
            streamingUnsupported = true;
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        if (accumulated.length() == 0) {
            streamingUnsupported = !sawEvent[0];
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        if (!sawEnd[0]) {
            // The body ended without message_stop, so the fragments are a prefix of the translation
            // and must not be cached as the whole of it. The plain path is the one that recovers: it
            // sends one non-streaming request whose body carries the complete answer, which keeps a
            // relay that always drops the tail usable instead of failing every line from now on.
            DebugLog.global().log("stream-fallback",
                    "provider", "claude:" + model,
                    "reason", "stream ended without an end marker",
                    "action", "fell back to a plain request",
                    "characters", Integer.toString(accumulated.length()));
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        requireCompleteAnswer(stopReason[0], budget);
        return TranslationOutputValidator.requireValid(text, accumulated.toString());
    }

    /**
     * Sends a one-token message through the configured model.
     *
     * @return the raw response body
     * @throws Exception when the key, the model or the response is unusable
     */
    public String probe() throws Exception {
        String response = post(PROBE_TEXT, "", 1, false);
        Object root = JsonStrings.parse(response);
        if (!(root instanceof Map) || !(((Map<?, ?>) root).get("content") instanceof List)) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("Claude", response));
        }
        return response;
    }

    /**
     * The catalog URL that belongs to this endpoint, so the settings screen can list models.
     *
     * @param endpoint configured messages endpoint
     * @return the sibling {@code /models} URL
     */
    static String modelsUrl(String endpoint) {
        String base = endpoint == null ? "" : endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(MESSAGES_SUFFIX)) {
            return base.substring(0, base.length() - MESSAGES_SUFFIX.length()) + MODELS_SUFFIX;
        }
        return base + MODELS_SUFFIX;
    }

    /** Joins every text fragment of the reply. */
    static String extractContent(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        return ProviderJson.joinField(response, "content", "text");
    }

    /**
     * Rejects an answer Anthropic reported as unfinished.
     *
     * <p>{@code stop_reason} is the only thing that separates a complete translation from the first
     * half of one: {@link TranslationOutputValidator} bounds the length from above but has no floor
     * that a half sentence would miss, and whatever passes it is written into the persistent cache,
     * so one truncated answer would be replayed for the rest of the installation with no further
     * request ever being made.
     *
     * <p>The field is read through {@link JsonStrings#readStringField} rather than a JSON path
     * because a body that is not JSON at all is reported by the caller's own diagnostics, and a
     * missing field must not turn into a parse failure here. A streamed call only reaches this
     * method once {@code message_stop} proved the stream ended, so an absent reason here means the
     * plain path, where an endpoint is free not to report one.
     *
     * @param stopReason value the endpoint reported, {@code null} when it reported none
     * @param budget     completion budget the request was sent with, quoted in the message
     * @throws IllegalStateException when generation stopped for any reason other than a clean end
     */
    private static void requireCompleteAnswer(String stopReason, int budget) {
        if (stopReason == null || STOP_REASON_END_TURN.equals(stopReason)) {
            return;
        }
        if (STOP_REASON_MAX_TOKENS.equals(stopReason)) {
            throw new IllegalStateException("Claude used up its " + budget
                    + "-token max_tokens budget before finishing the translation; raise the budget"
                    + " or shorten the text");
        }
        throw new IllegalStateException("Claude stopped early (stop_reason: " + stopReason
                + "); shorten the text or try another model");
    }

    /** Completion budget for one text; {@code max_tokens} is required by this API. */
    static int completionBudget(String text) {
        int requested = Math.min(text.length() * 2 + 32, MAXIMUM_TOKENS_LIMIT);
        return Math.max(MINIMUM_COMPLETION_TOKENS, requested);
    }

    private String post(String text, String system, int maximumTokens, boolean streaming)
            throws Exception {
        return http.post(endpoint, requestBody(text, system, maximumTokens, streaming), headers());
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", API_VERSION);
        return headers;
    }

    /** One Messages request body; {@code streaming} selects the event-stream response form. */
    private String requestBody(String text, String system, int maximumTokens, boolean streaming) {
        return requestBody(text, system, maximumTokens, streaming, false);
    }

    /**
     * One Messages request body.
     *
     * @param jsonObject start the JSON object for the model with a prefilled assistant turn, which is
     *                   how Anthropic asks for one — the API has no output-format field. The reply
     *                   continues from that brace, so a caller using this mode has to put the same
     *                   character back in front of {@code content[].text} before parsing it.
     */
    String requestBody(String text, String system, int maximumTokens, boolean streaming,
            boolean jsonObject) {
        StringBuilder body = new StringBuilder(text.length() + 256)
                .append('{')
                .append("\"model\":").append(JsonStrings.quote(model)).append(',')
                .append("\"max_tokens\":").append(maximumTokens).append(',')
                .append("\"temperature\":0,");
        if (system != null && !system.trim().isEmpty()) {
            // A top-level field here, not a message: Anthropic rejects role "system" in messages.
            body.append("\"system\":").append(JsonStrings.quote(system)).append(',');
        }
        body.append("\"messages\":[{\"role\":\"user\",\"content\":")
                .append(JsonStrings.quote(text)).append("}");
        if (jsonObject) {
            body.append(",{\"role\":\"assistant\",\"content\":").append(JsonStrings.quote("{"));
        }
        body.append(']');
        return body.append(streaming ? ",\"stream\":true}" : ",\"stream\":false}").toString();
    }

    /**
     * Claude is asked for a JSON object with a prefilled assistant turn.
     *
     * <p>Declared here so a batch path can ask in this provider's own dialect instead of the shared
     * request builder guessing; Claude has no batch path yet.
     */
    public static JsonOutputMode jsonOutputMode() {
        return JsonOutputMode.PREFILL;
    }

    private static boolean isEventStream(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");
    }

    private static String requireModel(String model) {
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("Claude model is required");
        }
        return model.trim();
    }
}
