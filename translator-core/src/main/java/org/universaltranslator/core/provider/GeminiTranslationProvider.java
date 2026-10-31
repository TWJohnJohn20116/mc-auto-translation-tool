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
import java.util.List;
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
    /**
     * Largest batch completion budget this endpoint accepted, per batch size, lowered only by an
     * actual refusal.
     *
     * <p>The batch path remembered nothing, so an endpoint whose output cap sits below the budget a
     * batch asked for answered 400 on every batch of the session, and each refusal was followed by
     * one request per line. See {@link BatchBudget}: the loop that learns the value is shared with
     * the other two batching providers, and {@link BatchBudget.ByEntries} keeps one small batch from
     * lowering the budget of every later batch of the session.
     */
    private final BatchBudget.ByEntries acceptedBatchTokens;
    /**
     * Largest number of texts one request has been observed to accept, lowered only by a refusal that
     * pointed at the batch itself.
     *
     * <p>Nothing learned it, so an endpoint whose context cannot hold the configured batch was sent the
     * same number of lines again on the next screen, and each attempt cost one refused or truncated
     * request plus one request per line. See {@link BatchBudget.ByBatchSize}.
     */
    private final BatchBudget.ByBatchSize acceptedBatchSizes;
    /** Set once this endpoint proved it cannot answer with an event stream. */
    private volatile boolean streamingUnsupported;
    /**
     * Earliest {@link System#nanoTime()} instant at which an event stream may be attempted again, or
     * {@code 0} when nothing is cooling down.
     *
     * <p>A stream that outlived its budget used to set {@link #streamingUnsupported} for good, so one
     * silent minute cost the early preview for the rest of the session with no way back. The cooldown
     * is what lets the endpoint recover on its own.
     */
    private volatile long streamingRetryAtNanos;
    /**
     * Groups nearby texts into one request.
     *
     * <p>{@code batch-size} was a dead setting for this provider: it implemented no gatherer, so a
     * forty-line screen sent forty requests where the OpenAI-compatible path sent five. The protocol
     * — a JSON object keyed by line number, read by
     * {@link OpenAiChatTranslationProvider#parseJsonBatch} — is provider-neutral and is reused
     * unchanged.
     */
    private final BatchGatherer gatherer;
    /** How one group of texts is translated, handed to {@link #gatherer}. */
    private final BatchGatherer.Work work;

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
        this(endpoint, apiKey, model, http, prompt, TranslationTuning.DEFAULTS);
    }

    GeminiTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt,
            TranslationTuning tuning
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
        TranslationTuning effectiveTuning = tuning == null ? TranslationTuning.DEFAULTS : tuning;
        this.acceptedBatchTokens = new BatchBudget.ByEntries(ESCALATED_TOKENS_LIMIT);
        this.acceptedBatchSizes = new BatchBudget.ByBatchSize(effectiveTuning.batchSize());
        this.gatherer = new BatchGatherer(effectiveTuning.batchSize(),
                effectiveTuning.batchWindowMillis(),
                effectiveTuning.maximumConcurrentBatches(),
                effectiveTuning.requestIntervalMillis());
        // OnlineProviderConfig puts every provider it builds inside ResilientTranslationProvider,
        // which retries the very failures the gatherer would resend a group for.
        this.gatherer.setOuterRetries(true);
        this.work = new BatchGatherer.Work() {
            @Override
            public String translateAlone(TranslationRequest request) throws Exception {
                return translateOne(request);
            }

            @Override
            public List<String> translateGroup(List<TranslationRequest> requests) throws Exception {
                return translateBatch(requests);
            }
        };
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

    /**
     * Translates one text, batching texts that arrive close together.
     *
     * <p>{@code batch-size} used to be a dead setting here: every line paid a request of its own, so
     * a forty-line screen cost forty round trips and forty TLS handshakes on the slowest, most
     * rate-limited providers in the list. A multi-line text stays on the single path, because
     * {@link TranslationPrompt#batch} asks for one entry per input line and a text that already
     * carries line breaks is one entry of its own either way.
     */
    @Override
    public String translate(TranslationRequest request) throws Exception {
        if (!gatherer.batching() || request.getText().indexOf('\n') >= 0) {
            return translateOne(request);
        }
        return gatherer.translate(request, work);
    }

    /** Translates exactly one text with one request. */
    private String translateOne(TranslationRequest request) throws Exception {
        String text = request.getText();
        DebugLog.global().logRequest("gemini:" + model, model, base,
                request.getKind().name(), text);
        int budget = completionBudget(text);
        String system = TranslationPrompt.single(prompt, request);
        String response = post(text, system, budget, false);
        Object root = JsonTree.parseOrNull(response);
        String translated = extractContent(root, response);
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
                root = JsonTree.parseOrNull(response);
                translated = extractContent(root, response);
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
            TranslationStats.global().recordParsedUsage("gemini:" + model, root);
        }
        DebugLog.global().logResponse("gemini:" + model, request.getKind().name(), translated);
        return TranslationOutputValidator.requireValid(text, translated);
    }

    /**
     * Sends every text of one group as one JSON object in a single request.
     *
     * <p>{@code generationConfig.responseMimeType} makes the candidate text the object itself, so
     * nothing has to be put back before parsing. Every entry the model did not answer stays
     * {@code null}, and {@link BatchGatherer} repairs exactly those lines with a request of their own.
     *
     * @return one entry per group member in order, with {@code null} for a line the reply did not
     *         answer
     */
    private List<String> translateBatch(List<TranslationRequest> group) throws Exception {
        String user = ProviderSupport.batchUserBody(group);
        String system = TranslationPrompt.batch(prompt, group.get(0));
        int budget = Math.min(ChatBudget.batchCompletionBudget(user, group.size(),
                ESCALATED_TOKENS_LIMIT, MINIMUM_COMPLETION_TOKENS),
                acceptedBatchTokens.forEntries(group.size()).budget());
        DebugLog.global().logRequest("gemini:" + model, model, base,
                group.get(0).getKind().name(), user);
        String response;
        try {
            response = BatchBudget.post(acceptedBatchTokens.forEntries(group.size()), budget,
                    MINIMUM_COMPLETION_TOKENS,
                    attempt -> http.post(generateUri, requestBody(user, system, attempt, true),
                            headers()));
        } catch (HttpStatusException rejected) {
            // A refusal that names the request's own length is about how many texts travelled in it:
            // nothing lowered that number, so every later batch was built the same size and refused
            // again, each time for one refused request plus one request per line.
            if (BatchBudget.refusedBecauseTooManyEntries(rejected, user, group)) {
                gatherer.setBatchSize(acceptedBatchSizes.rememberTooLarge(group.size()));
            }
            throw rejected;
        }
        Object root = JsonTree.parseOrNull(response);
        if (response != null && response.indexOf("\"usageMetadata\"") >= 0) {
            TranslationStats.global().recordParsedUsage("gemini:" + model, root);
        }
        String content = extractContent(root, response);
        // A reply that stopped at the output budget is a truncated object, and its last entry would
        // look like a short translation. Reporting the batch as unusable sends every line down the
        // single path, where the budget is escalated and each answer is judged on its own.
        String finishReason = JsonStrings.readStringField(response, FINISH_REASON_FIELD);
        if (content == null
                || (finishReason != null && !FINISH_REASON_STOP.equals(finishReason))) {
            if (FINISH_REASON_MAX_TOKENS.equals(finishReason)) {
                // The output budget ran out before the object was closed, and the number of entries is
                // what decides how much output one batch needs: remembering it here is the only way the
                // next screen stops asking for the same impossible answer.
                gatherer.setBatchSize(acceptedBatchSizes.rememberTooLarge(group.size()));
            }
            throw new IllegalStateException(ProviderJson.describeMissingContent("Gemini", response));
        }
        // Nothing has to be put back here: responseMimeType makes the candidate text the object
        // itself. The numbered list is still a fallback, because a relay that drops the generation
        // config answers with the prose form the historical protocol accepted.
        List<String> parsed = ProviderSupport.parseBatchReply(content, group.size(), false);
        if (parsed == null) {
            gatherer.setBatchSize(acceptedBatchSizes.rememberTooLarge(group.size()));
            throw new IllegalStateException("Gemini answered a batch without a JSON object");
        }
        DebugLog.global().logResponse("gemini:" + model, group.get(0).getKind().name(),
                parsed.size() + " entries");
        return parsed;
    }

    @Override
    public String translateStreaming(TranslationRequest request, TranslationStreamListener listener)
            throws Exception {
        if (listener == null) {
            return translate(request);
        }
        if (streamingUnsupported
                || StreamCompletionMemory.coolingDown(streamingRetryAtNanos)
                || request.getText().indexOf('\n') >= 0) {
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        String text = request.getText();
        int budget = completionBudget(text);
        StringBuilder accumulated = new StringBuilder(64);
        // Copying the whole accumulation on every delta made one translation copy O(n^2) characters
        // and pushed a full string at the UI per delta. The step and the closing publish live in the
        // shared publisher, so this provider cannot drift from the other two.
        ProviderSupport.PreviewPublisher preview = new ProviderSupport.PreviewPublisher(listener);
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
            // parser's payload bound never sees a comment. The budget is the one
            // {@code api-read-timeout-ms} already decided for this response: a second, hidden 180 s
            // constant here ignored the setting in both directions, and a ceiling kept here instead
            // of in ProviderSupport made the three streaming providers disagree.
            long deadlineNanos = response.getDeadlineNanos();
            ServerSentEvents.read(StreamWatchdog.arm(response.getBody(),
                    ProviderSupport.stallBudgetMillis(response)), payload -> {
                // One parse per event: the fragments and the finish reason are fields of the same
                // document, and looking them up separately rebuilt the tree once per field.
                Object event = JsonTree.parseOrNull(payload);
                String fragment = ProviderJson.joinField(event,
                        "candidates[0].content.parts", "text");
                if (fragment != null && !fragment.isEmpty()) {
                    accumulated.append(fragment);
                    // One callback per PARTIAL_TEXT_STEP_CHARS instead of one per delta: every
                    // callback used to copy the whole accumulation, which is O(n^2) per line.
                    preview.append(accumulated);
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
                    // The finish reason is this protocol's only end marker, so nothing can follow
                    // it: reading on made the watchdog time out — SSE does not require the relay to
                    // close the body — and turned a finished answer into an IOException.
                    return false;
                }
                return true;
            }, deadlineNanos);
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
                // every later line would pay the same wait before failing over. A cooldown replaces
                // the permanent flag: one silent minute must not cost the early preview for the rest
                // of the session, and the endpoint has to be able to recover on its own.
                streamingRetryAtNanos = StreamCompletionMemory.cooldownUntilNow();
                DebugLog.global().logStreamEvent("gemini:" + model, "streaming-cooldown", 0);
            }
            if (!sawEnd[0]) {
                throw broken;
            }
            // The end marker had already arrived, so the accumulation is the whole answer and the
            // break belongs to a relay that keeps the body open after its last chunk. Escaping here
            // discarded a finished translation and, because the preview was already published, that
            // line never received a result again in this session.
            DebugLog.global().logStreamEvent("gemini:" + model, "end-before-break", 0);
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
                // A cooldown, not the permanent flag: StreamCompletionMemory says a dropped stream
                // must cost a pause, and its own class comment names that behaviour as the contract.
                streamingRetryAtNanos = StreamCompletionMemory.cooldownUntilNow();
                DebugLog.global().logStreamEvent("gemini:" + model, "streaming-abandoned", 0);
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
        // The throttle may have held the tail back; the preview has to end up complete.
        preview.finish(accumulated);
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
        return extractContent(JsonTree.parseOrNull(response), response);
    }

    /**
     * The same join over an already parsed reply.
     *
     * <p>The single-text path needs the fragments, the finish reason and the token counts of one body,
     * and reading each of them from the raw text built the same document twice more.
     *
     * @param root the reply as returned by {@code JsonTree.parseOrNull}, or {@code null}
     * @param response the same body as text
     * @return the joined fragments, or {@code null} when the reply carried none
     */
    static String extractContent(Object root, String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        return ProviderJson.joinField(root, "candidates[0].content.parts", "text");
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
     * request builder guessing; {@link #translateBatch} is that caller.
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
