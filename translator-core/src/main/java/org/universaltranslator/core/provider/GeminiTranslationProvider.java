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

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
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
    /**
     * Largest {@code maxOutputTokens} a truncated answer is retried with. Gemini 2.5 models spend
     * {@code maxOutputTokens} on thinking before they write anything, so the historical 2048 ceiling
     * is not enough for a long line at all.
     */
    private static final int ESCALATED_TOKENS_LIMIT = 8192;
    /**
     * Field that reports why the model stopped. It is the only signal that tells a finished
     * translation apart from the first half of one, so it is read from a plain reply and from the
     * last event of a stream alike.
     */
    private static final String FINISH_REASON_FIELD = "finishReason";
    /** {@code finishReason} of a candidate the model finished on its own. */
    private static final String FINISH_REASON_STOP = "STOP";
    /** {@code finishReason} of a candidate cut off at the output budget. */
    private static final String FINISH_REASON_MAX_TOKENS = "MAX_TOKENS";
    /** The probe only has to prove the key and the model resolve, so it asks for one token. */
    private static final String PROBE_TEXT = "ping";
    /**
     * Characters the accumulation has to grow by before the listener is handed a new snapshot.
     * Without a threshold every delta copied the whole accumulation and pushed a full string at the
     * UI, which is quadratic in the length of the answer.
     */
    private static final int PARTIAL_TEXT_STEP_CHARS = 32;

    private final String base;
    /** The two request URLs, resolved once: the endpoint cannot change after construction. */
    private final URI generateUri;
    private final URI streamUri;
    private final String apiKey;
    private final String model;
    private final TranslationPrompt.Settings prompt;
    private final HttpJsonClient http;
    /** The value {@link #id()} reports; see {@link ProviderCacheKey}. */
    private final String id;
    /** Remembers an endpoint whose event stream never declares its own end. */
    private final StreamCompletionMemory streamMemory = new StreamCompletionMemory();
    /**
     * Largest {@code maxOutputTokens} this model has been observed to accept. A thinking answer that
     * was cut off at {@link #MAXIMUM_TOKENS_LIMIT} is retried once above it, and the value that
     * worked is remembered so the next line does not have to discover it again.
     */
    private volatile int acceptedOutputTokens = MAXIMUM_TOKENS_LIMIT;
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
        this.base = collectionBase(endpoint == null || endpoint.trim().isEmpty()
                ? DEFAULT_ENDPOINT : endpoint.trim());
        // Validate at construction like the other providers, so a plaintext remote host is refused
        // before any credential could be sent rather than on the first translation.
        EndpointPolicy.requireSafeEndpoint(this.base);
        this.apiKey = ProviderSupport.requireCredential("Gemini API key", apiKey);
        this.model = requireModel(model);
        this.generateUri = URI.create(this.base + "/" + this.model + GENERATE);
        this.streamUri = URI.create(this.base + "/" + this.model + STREAM_GENERATE + "?alt=sse");
        this.http = http;
        this.prompt = prompt == null ? TranslationPrompt.Settings.standard() : prompt;
        this.id = ProviderCacheKey.of("gemini", this.model, URI.create(this.base),
                "x-goog-api-key", this.prompt.signature());
    }

    /**
     * The provider id: {@code gemini:model@endpointDigest[#promptSignature]}.
     *
     * <p>{@code gemini-endpoint} is configurable, and pointing it at a self-hosted proxy used to keep
     * the same id as the public endpoint, so the previous server's translation was replayed from the
     * disk cache without a single request being sent. The endpoint digest is what separates them; the
     * model and the prompt signature stay in the readable part so diagnostics remain legible. See
     * {@link ProviderCacheKey} — no key material is hashed, so rotating a credential keeps the cache.
     */
    @Override
    public String id() {
        return id;
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        String text = request.getText();
        DebugLog.global().logRequest("gemini:" + model, model, base,
                request.getKind().name(), text);
        int budget = completionBudget(text);
        String system = TranslationPrompt.single(prompt, request);
        String response = post(text, system, budget, false);
        String translated = extractContent(response);
        String finishReason = JsonStrings.readStringField(response, FINISH_REASON_FIELD);
        if (FINISH_REASON_MAX_TOKENS.equals(finishReason)) {
            // The budget ran out mid-answer. Reporting "raise the budget" was useless: nothing in the
            // provider or in the settings screen could raise it, so a long line failed the same way
            // for the whole session. Ask once more with a larger budget and remember the value that
            // was enough — the same two-stage shape the OpenAI-compatible provider uses.
            int escalated = escalatedBudget();
            if (escalated > budget) {
                response = post(text, system, escalated, false);
                // Quote the budget the request really used: reporting the pre-escalation value in
                // the failure message sent the reader looking for a limit that no longer applied.
                budget = escalated;
                translated = extractContent(response);
                finishReason = JsonStrings.readStringField(response, FINISH_REASON_FIELD);
                if (translated != null && !translated.trim().isEmpty()
                        && !FINISH_REASON_MAX_TOKENS.equals(finishReason)) {
                    acceptedOutputTokens = escalated;
                }
            }
        }
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("Gemini", response));
        }
        // Completeness is only worth asking about once there is content to judge: an empty answer is
        // explained far better by the provider's own message and the body excerpt than by a finish
        // reason, while a cut-off answer is distinguishable from a short one only here.
        requireCompleteAnswer(finishReason, budget);
        if (response.indexOf("\"usageMetadata\"") >= 0) {
            TranslationStats.global().recordUsage("gemini:" + model, response);
        }
        DebugLog.global().logResponse("gemini:" + model, request.getKind().name(), translated);
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
        // Characters already handed to the listener. Copying the whole accumulation on every delta
        // made one translation copy O(n^2) characters and pushed a full string at the UI per delta.
        int[] published = new int[1];
        // Only the last chunk of a stream reports a finish reason, so that report is both the
        // truncation signal and this protocol's only end marker: streamGenerateContent ends by
        // closing the array, which an interrupted connection does just as well.
        String[] finishReason = new String[1];
        boolean[] sawEnd = new boolean[1];
        try (HttpJsonClient.StreamedResponse response = http.postStreaming(streamUri, requestBody(
                        text, TranslationPrompt.single(prompt, request), budget), headers())) {
            if (!EventStream.isEventStream(response.getContentType())) {
                streamingUnsupported = true;
                return TranslationProvider.super.translateStreaming(request, listener);
            }
            // Bounded by wall clock, cancellation and raw bytes: a relay that only sends keep-alive
            // comments would otherwise hold this worker for the rest of the session, because the
            // parser's payload bound never sees a comment.
            ServerSentEvents.read(StreamWatchdog.arm(response.getBody()), payload -> {
                // One parse per event: the fragments and the finish reason are fields of the same
                // document, and looking them up separately rebuilt the tree once per field.
                Object event = JsonTree.parseOrNull(payload);
                String fragment = ProviderJson.joinField(event,
                        "candidates[0].content.parts", "text");
                if (fragment != null && !fragment.isEmpty()) {
                    accumulated.append(fragment);
                    // One callback per PARTIAL_TEXT_STEP_CHARS instead of one per delta: every
                    // callback used to copy the whole accumulation, which is O(n^2) per line.
                    if (accumulated.length() - published[0] >= PARTIAL_TEXT_STEP_CHARS) {
                        published[0] = accumulated.length();
                        listener.onPartialText(accumulated.toString());
                    }
                }
                // finishReason sits inside the candidate object, so it is found with the field scan
                // rather than a fixed path; the scan does not parse, so it costs nothing here.
                String reported = JsonStrings.readStringField(payload, FINISH_REASON_FIELD);
                if (reported != null) {
                    // A reported reason proves the model reached the end of its answer, whatever
                    // the reason is; an absent one proves nothing, which is why the two cases are
                    // tracked separately instead of sharing a null.
                    sawEnd[0] = true;
                    DebugLog.global().logStreamEvent("gemini:" + model, "end", 0);
                    if (!FINISH_REASON_STOP.equals(reported)) {
                        finishReason[0] = reported;
                    }
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
        } catch (IOException broken) {
            if (StreamWatchdog.isLimitExceeded(broken)) {
                // This endpoint could not deliver an event stream inside the watchdog's budget, so
                // every later line would pay the same three minutes before failing over.
                streamingUnsupported = true;
            }
            throw broken;
        }
        if (accumulated.length() == 0) {
            // Nothing usable came back, including the case of an event stream carrying only a
            // block reason. The plain path is the one that reports why, so it takes over. The
            // permanent flag is deliberately not set: an endpoint that answered "nothing to
            // translate" has not proved anything about whether it can stream.
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        if (!sawEnd[0]) {
            // The body ended before any chunk reported a finish reason, so what arrived is a prefix
            // of the translation and must not be cached as the whole of it. The plain path is the
            // one that recovers: it sends one non-streaming request whose body carries the complete
            // answer, which keeps an endpoint that never streams a finish reason usable instead of
            // failing every line from now on.
            DebugLog.global().log("stream-fallback",
                    "provider", "gemini:" + model,
                    "reason", "stream ended without an end marker",
                    "action", "fell back to a plain request",
                    "characters", Integer.toString(accumulated.length()));
            if (streamMemory.rememberIncomplete()) {
                // Recording the doubling without remembering it made every later line pay it again.
                streamingUnsupported = true;
            }
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        streamMemory.rememberComplete();
        if (finishReason[0] != null) {
            // The stream itself reported that generation stopped early — in practice MAX_TOKENS,
            // because thinking tokens eat the output budget. The plain path is where the budget is
            // escalated and the request retried, so handing over is what keeps a long line
            // recoverable; the partial text already published is replaced by the complete answer.
            DebugLog.global().log("stream-fallback",
                    "provider", "gemini:" + model,
                    "reason", "stream ended early: " + finishReason[0],
                    "action", "fell back to a plain request",
                    "characters", Integer.toString(accumulated.length()));
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        if (published[0] != accumulated.length()) {
            // The throttle may have held the tail back; the preview has to end up complete.
            listener.onPartialText(accumulated.toString());
        }
        return TranslationOutputValidator.requireValid(text, accumulated.toString());
    }

    /**
     * Sends a one-token generation through the configured model.
     *
     * <p>Existing with a {@code candidates} array is not enough: a thinking model spends that token
     * on its chain of thought and answers with no text at all, which looked healthy here while every
     * translation failed. The content itself has to be non-empty.
     *
     * @return the raw response body
     * @throws Exception when the key, the model or the response is unusable
     */
    public String probe() throws Exception {
        String response = post(PROBE_TEXT, "", 1, false);
        Object root = JsonTree.parseOrNull(response);
        if (!(root instanceof Map) || !(((Map<?, ?>) root).get("candidates") instanceof java.util.List)) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("Gemini", response));
        }
        String answer = ProviderJson.joinField(root, "candidates[0].content.parts", "text");
        if (answer == null || answer.trim().isEmpty()) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("Gemini", response));
        }
        return response;
    }

    /** Joins every text fragment of the first candidate; the body is parsed once. */
    static String extractContent(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        return ProviderJson.joinField(response, "candidates[0].content.parts", "text");
    }

    /**
     * Rejects an answer Gemini reported as unfinished.
     *
     * <p>{@code finishReason} is the only thing that separates a complete translation from the
     * first half of one: {@link TranslationOutputValidator} bounds the length from above but has no
     * floor that a half sentence would miss, and whatever passes it is written into the persistent
     * cache, so one truncated answer would be replayed for the rest of the installation with no
     * further request ever being made.
     *
     * <p>The field is read through {@link JsonStrings#readStringField} rather than a JSON path
     * because a body that is not JSON at all is reported by the caller's own diagnostics, and a
     * missing field must not turn into a parse failure here. A streamed call only reaches this
     * method once the stream proved it ended, so an absent reason here means the plain path, where
     * an endpoint is free not to report one.
     *
     * @param finishReason value the endpoint reported, {@code null} when it reported none
     * @param budget       completion budget the request was sent with, quoted in the message
     * @throws IllegalStateException when generation stopped for any reason other than a clean end
     */
    private static void requireCompleteAnswer(String finishReason, int budget) {
        if (finishReason == null || FINISH_REASON_STOP.equals(finishReason)) {
            return;
        }
        if (FINISH_REASON_MAX_TOKENS.equals(finishReason)) {
            throw new IllegalStateException("Gemini used up its " + budget
                    + "-token output budget before finishing the translation; raise the budget or"
                    + " shorten the text");
        }
        throw new IllegalStateException("Gemini stopped early (finishReason: " + finishReason
                + "); shorten the text or try another model");
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
        String base = collectionBase(endpoint == null || endpoint.trim().isEmpty()
                ? DEFAULT_ENDPOINT : endpoint.trim());
        return base.endsWith("/models") ? base : base + "/models";
    }

    /**
     * Truncates a configured endpoint back to the models collection.
     *
     * <p>The request URLs always append {@code /<model><method>}, so a pasted full method
     * URL ({@code .../models/gemini-2.0-flash:generateContent}) would otherwise produce
     * {@code .../models/gemini-2.0-flash/gemini-2.0-flash:generateContent}. The collection base is
     * the only usable form here, which is why {@link #modelsUrl} already normalised to it.
     */
    private static String collectionBase(String endpoint) {
        String base = stripMethod(endpoint);
        int marker = base.lastIndexOf("/models/");
        if (marker >= 0) {
            base = base.substring(0, marker + "/models".length());
        }
        return base;
    }

    /**
     * Completion budget for one text. The same scaling the OpenAI-compatible provider uses, so a
     * long line is not truncated mid-sentence and then accepted as complete.
     *
     * <p>Once a larger value proved to be needed it becomes the ceiling, so the second line of the
     * same kind starts where the first one finished instead of paying for the same truncation again.
     */
    private int completionBudget(String text) {
        int ceiling = Math.max(MAXIMUM_TOKENS_LIMIT, acceptedOutputTokens);
        return ChatBudget.completionBudget(text, ceiling, MINIMUM_COMPLETION_TOKENS);
    }

    /** The larger budget one truncated answer is retried with. */
    private int escalatedBudget() {
        int next = Math.max(MAXIMUM_TOKENS_LIMIT * 2, acceptedOutputTokens);
        return Math.min(ESCALATED_TOKENS_LIMIT, next);
    }

    private String post(String text, String system, int maximumTokens, boolean streaming)
            throws Exception {
        // The endpoint was validated when the provider was built, and both URLs are resolved then
        // too: re-running the endpoint policy here cost a Pattern compile per check on every line
        // and threw away the corrected URI it returned.
        return http.post(streaming ? streamUri : generateUri,
                requestBody(text, system, maximumTokens), headers());
    }

    private Map<String, String> headers() {
        return Collections.singletonMap("x-goog-api-key", apiKey);
    }

    /** One {@code generateContent} request body. */
    private String requestBody(String text, String system, int maximumTokens) {
        return requestBody(text, system, maximumTokens, false);
    }

    /**
     * One {@code generateContent} request body.
     *
     * @param jsonObject ask for a JSON object through the generation config; Gemini answers with the
     *                   object as the candidate text, so nothing on the reply side changes
     */
    String requestBody(String text, String system, int maximumTokens, boolean jsonObject) {
        StringBuilder body = new StringBuilder(text.length() + 320).append('{');
        if (system != null && !system.trim().isEmpty()) {
            // Closes the part, the parts array and the systemInstruction object, in that order:
            // "}]}" rather than "]}]", which left the object open and every request a 400.
            body.append("\"systemInstruction\":{\"parts\":[{\"text\":")
                    .append(JsonStrings.quote(system)).append("}]},");
        }
        body.append("\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":")
                .append(JsonStrings.quote(text)).append("}]}]")
                .append(",\"generationConfig\":{\"temperature\":0,\"maxOutputTokens\":")
                .append(maximumTokens)
                .append(jsonObject ? ",\"responseMimeType\":\"application/json\"" : "")
                .append('}');
        return body.append('}').toString();
    }

    /**
     * Gemini can be asked for a JSON object through {@code generationConfig.responseMimeType}.
     *
     * <p>Declared here so a batch path can ask in this provider's own dialect instead of the shared
     * request builder guessing; Gemini has no batch path yet.
     */
    public static JsonOutputMode jsonOutputMode() {
        return JsonOutputMode.RESPONSE_MIME_TYPE;
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
        String value = model.trim();
        // The ListModels reply puts the resource name ("models/gemini-2.5-flash") in "name" and the
        // bare id in "baseModelId", so the settings screen can store either form. Only the bare id
        // belongs in the URL, because generateUrl already supplies the /models segment.
        // A model pasted straight from the API reference also carries the method
        // (".../gemini-2.5-flash:generateContent"); keeping it produced
        // "{model}:generateContent:generateContent" and an unexplainable 404, so the same cleanup
        // the endpoint gets is applied here.
        String stripped = stripMethod(value);
        int segment = stripped.lastIndexOf('/');
        if (segment >= 0) {
            stripped = stripped.substring(segment + 1);
        }
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("Gemini model is required");
        }
        return stripped;
    }
}
