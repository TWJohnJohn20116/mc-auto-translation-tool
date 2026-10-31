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
import java.util.LinkedHashMap;
import java.util.List;
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
     * Largest {@code max_tokens} a truncated answer is retried with. {@code max_tokens} is mandatory
     * on this API, so a long line that hits the ceiling had no way of ever finishing before.
     */
    private static final int ESCALATED_TOKENS_LIMIT = 8192;
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
    /** The value {@link #id()} reports; see {@link ProviderCacheKey}. */
    private final String id;
    /** Remembers an endpoint whose event stream never declares its own end. */
    private final StreamCompletionMemory streamMemory = new StreamCompletionMemory();
    /**
     * Largest {@code max_tokens} this model has been observed to accept. An answer cut off at
     * {@link #MAXIMUM_TOKENS_LIMIT} is retried once above it, and the value that worked is
     * remembered so the next line starts there.
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
        this(endpoint, apiKey, model, http, prompt, TranslationTuning.DEFAULTS);
    }

    ClaudeTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt,
            TranslationTuning tuning
    ) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(
                endpoint == null || endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint);
        this.apiKey = ProviderSupport.requireCredential("Claude API key", apiKey);
        this.model = requireModel(model);
        this.http = http;
        this.prompt = prompt == null ? TranslationPrompt.Settings.standard() : prompt;
        this.id = ProviderCacheKey.of("claude", this.model, this.endpoint,
                "anthropic-version=" + API_VERSION, this.prompt.signature());
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
     * The provider id: {@code claude:model@endpointDigest[#promptSignature]}.
     *
     * <p>{@code claude-endpoint} is configurable, and pointing it at a self-hosted proxy kept the
     * same id as the public endpoint, so the previous server's translation was replayed from the
     * disk cache without a request being sent. The pinned {@code anthropic-version} header is part of
     * the digest as well, because Anthropic may answer a different version differently. See
     * {@link ProviderCacheKey}: no key material is hashed.
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
        DebugLog.global().logRequest("claude:" + model, model, endpoint.toString(),
                request.getKind().name(), text);
        int budget = completionBudget(text);
        String system = TranslationPrompt.single(prompt, request);
        String response = post(text, system, budget, false);
        Object root = JsonTree.parseOrNull(response);
        String translated = extractContent(root, response);
        String stopReason = JsonStrings.readStringField(response, STOP_REASON_FIELD);
        if (STOP_REASON_MAX_TOKENS.equals(stopReason)) {
            // The answer hit max_tokens. Reporting "raise the budget" was useless — nothing in the
            // provider or the settings screen could raise it — so a long line failed the same way for
            // the whole session. Ask once more with a larger budget and remember the value that was
            // enough, the same two-stage shape the OpenAI-compatible provider uses.
            int escalated = escalatedBudget();
            if (escalated > budget) {
                response = post(text, system, escalated, false);
                // Quote the budget the request really used: reporting the pre-escalation value in
                // the failure message sent the reader looking for a limit that no longer applied.
                budget = escalated;
                root = JsonTree.parseOrNull(response);
                translated = extractContent(root, response);
                stopReason = JsonStrings.readStringField(response, STOP_REASON_FIELD);
                if (translated != null && !translated.trim().isEmpty()
                        && !STOP_REASON_MAX_TOKENS.equals(stopReason)) {
                    acceptedOutputTokens = escalated;
                }
            }
        }
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("Claude", response));
        }
        // Completeness is only worth asking about once there is content to judge: an empty answer is
        // explained far better by the provider's own message and the body excerpt than by a stop
        // reason, while a cut-off answer is distinguishable from a short one only here.
        requireCompleteAnswer(stopReason, budget);
        if (response.indexOf("\"usage\"") >= 0) {
            TranslationStats.global().recordParsedUsage("claude:" + model, root);
        }
        DebugLog.global().logResponse("claude:" + model, request.getKind().name(), translated);
        return TranslationOutputValidator.requireValid(text, translated);
    }

    /**
     * Sends every text of one group as one JSON object in a single request.
     *
     * <p>Anthropic has no output-format field, so the object is requested with a prefilled assistant
     * turn holding the opening brace — the reply continues from that brace, which is why the brace is
     * put back first. A relay that ignores the prefill answers with the whole object, and a small
     * model answers with a numbered list; both are read as well rather than throwing a batch away.
     * Every entry the model did not answer stays
     * {@code null}, and {@link BatchGatherer} repairs exactly those lines with a request of their own.
     *
     * @return one entry per group member in order, with {@code null} for a line the reply did not
     *         answer
     */
    private List<String> translateBatch(List<TranslationRequest> group) throws Exception {
        String user = ProviderSupport.batchUserBody(group);
        String system = TranslationPrompt.batch(prompt, group.get(0));
        // 8192 is what one truncated line is escalated to, not what a batch starts at: an endpoint
        // whose own cap is 4096 answered 400 to every batch of the session, and each refusal cost a
        // request per line afterwards. The single path's ceiling is the one to start from; the
        // reduction loop below discovers anything lower once and remembers it.
        int budget = Math.min(ChatBudget.batchCompletionBudget(user, group.size(),
                Math.max(MAXIMUM_TOKENS_LIMIT, acceptedOutputTokens), MINIMUM_COMPLETION_TOKENS),
                acceptedBatchTokens.forEntries(group.size()).budget());
        DebugLog.global().logRequest("claude:" + model, model, endpoint.toString(),
                group.get(0).getKind().name(), user);
        String response;
        try {
            response = BatchBudget.post(acceptedBatchTokens.forEntries(group.size()), budget,
                    MINIMUM_COMPLETION_TOKENS,
                    attempt -> http.post(endpoint, requestBody(user, system, attempt, false, true),
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
        if (response != null && response.indexOf("\"usage\"") >= 0) {
            TranslationStats.global().recordParsedUsage("claude:" + model, root);
        }
        String content = extractContent(root, response);
        // A reply cut off at max_tokens is a truncated object, and its last entry would look like a
        // short translation. Reporting the batch as unusable sends every line down the single path,
        // where the budget is escalated and each answer is judged on its own.
        String stopReason = JsonStrings.readStringField(response, STOP_REASON_FIELD);
        if (content == null || (stopReason != null && !STOP_REASON_END_TURN.equals(stopReason))) {
            if (STOP_REASON_MAX_TOKENS.equals(stopReason)) {
                // The output budget ran out before the object was closed, and the number of entries is
                // what decides how much output one batch needs: remembering it here is the only way the
                // next screen stops asking for the same impossible answer.
                gatherer.setBatchSize(acceptedBatchSizes.rememberTooLarge(group.size()));
            }
            throw new IllegalStateException(ProviderJson.describeMissingContent("Claude", response));
        }
        List<String> parsed = ProviderSupport.parseBatchReply(content, group.size(), true);
        if (parsed == null) {
            gatherer.setBatchSize(acceptedBatchSizes.rememberTooLarge(group.size()));
            throw new IllegalStateException("Claude answered a batch without a JSON object");
        }
        DebugLog.global().logResponse("claude:" + model, group.get(0).getKind().name(),
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
        // A stream reports how generation ended in its message_delta event, and message_stop is its
        // own statement that the answer is complete; both have to be captured while the body is
        // still being read. They are separate because only the latter proves the stream ended: a
        // connection that died mid-answer never sends it.
        String[] stopReason = new String[1];
        boolean[] sawEnd = new boolean[1];
        try (HttpJsonClient.StreamedResponse response = http.postStreaming(endpoint, requestBody(
                text, TranslationPrompt.single(prompt, request), budget, true),
                headers())) {
            if (!EventStream.isEventStream(response.getContentType())) {
                streamingUnsupported = true;
                return TranslationProvider.super.translateStreaming(request, listener);
            }
            // Bounded by wall clock, cancellation and raw bytes, so a relay that only sends
            // keep-alive comments cannot hold this worker for the rest of the session. The budget is
            // the one {@code api-read-timeout-ms} already decided for this response: a second,
            // hidden 180 s constant here ignored the setting in both directions, and a ceiling kept
            // here instead of in ProviderSupport made the three streaming providers disagree about
            // how long one silent stream may live.
            long deadlineNanos = response.getDeadlineNanos();
            ServerSentEvents.read(StreamWatchdog.arm(response.getBody(),
                    ProviderSupport.stallBudgetMillis(response)), payload -> {
                // One parse per event: the type, the stop reason, the text and the error test are
                // fields of the same document, and looking each up separately rebuilt the whole tree
                // five times for every fragment of the answer.
                Object event = JsonTree.parseOrNull(payload);
                if ("message_stop".equals(JsonTree.stringAt(event, "type"))) {
                    sawEnd[0] = true;
                    DebugLog.global().logStreamEvent("claude:" + model, "end", 0);
                    return false;
                }
                // The stop reason sits inside "delta", so it is found with the field scan rather than a
                // fixed path; the scan does not parse, so it costs nothing here.
                String reported = JsonStrings.readStringField(payload, STOP_REASON_FIELD);
                if (reported != null && !STOP_REASON_END_TURN.equals(reported)) {
                    stopReason[0] = reported;
                }
                String fragment = JsonTree.stringAt(event, "delta.text");
                if (fragment == null) {
                    fragment = JsonTree.stringAt(event, "content_block.text");
                }
                if (fragment != null && !fragment.isEmpty()) {
                    accumulated.append(fragment);
                    // One callback per PARTIAL_TEXT_STEP_CHARS instead of one per delta: every
                    // callback used to copy the whole accumulation, which is O(n^2) per line.
                    preview.append(accumulated);
                } else if (JsonStrings.readStringField(payload, "error") != null) {
                    // Anthropic reports a failure as an ordinary event behind HTTP 200; stop and let
                    // the plain path produce the message the user has always seen.
                    return false;
                }
                return true;
            }, deadlineNanos);
        } catch (HttpStatusException rejected) {
            if (rejected.isRetryable()) {
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
                DebugLog.global().logStreamEvent("claude:" + model, "streaming-cooldown", 0);
            }
            throw broken;
        }
        if (accumulated.length() == 0) {
            // Nothing usable came back, including an event stream carrying only an error event. The
            // plain path is the one that reports why, so it takes over. The permanent flag is
            // deliberately not set: an endpoint that answered "nothing to translate" has not proved
            // anything about whether it can stream.
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
            if (streamMemory.rememberIncomplete()) {
                // Recording the doubling without remembering it made every later line pay it again.
                // A cooldown, not the permanent flag: StreamCompletionMemory says a dropped stream
                // must cost a pause, and its own class comment names that behaviour as the contract.
                streamingRetryAtNanos = StreamCompletionMemory.cooldownUntilNow();
                DebugLog.global().logStreamEvent("claude:" + model, "streaming-abandoned", 0);
            }
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        streamMemory.rememberComplete();
        if (stopReason[0] != null) {
            // The stream itself reported that generation stopped early — in practice max_tokens.
            // The plain path is where the budget is escalated and the request retried, so handing
            // over is what keeps a long line recoverable; the partial text already published is
            // replaced by the complete answer.
            DebugLog.global().log("stream-fallback",
                    "provider", "claude:" + model,
                    "reason", "stream ended early: " + stopReason[0],
                    "action", "fell back to a plain request",
                    "characters", Integer.toString(accumulated.length()));
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        // The throttle may have held the tail back; the preview has to end up complete.
        preview.finish(accumulated);
        return TranslationOutputValidator.requireValid(text, accumulated.toString());
    }

    /**
     * Sends a one-token message through the configured model.
     *
     * <p>Existing with a {@code content} array is not enough: a thinking model spends that token on
     * its chain of thought and answers with no text at all, which looked healthy here while every
     * translation failed. The text itself has to be non-empty.
     *
     * @return the raw response body
     * @throws Exception when the key, the model or the response is unusable
     */
    public String probe() throws Exception {
        String response = post(PROBE_TEXT, "", 1, false);
        Object root = JsonTree.parseOrNull(response);
        if (!(root instanceof Map) || !(((Map<?, ?>) root).get("content") instanceof List)) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("Claude", response));
        }
        String answer = ProviderJson.joinField(root, "content", "text");
        if (answer == null || answer.trim().isEmpty()) {
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
        return extractContent(JsonTree.parseOrNull(response), response);
    }

    /**
     * The same join over an already parsed reply.
     *
     * <p>The single-text path needs the fragments, the stop reason and the token counts of one body,
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
        return ProviderJson.joinField(root, "content", "text");
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

    /**
     * Completion budget for one text; {@code max_tokens} is required by this API.
     *
     * <p>Once a larger value proved to be needed it becomes the ceiling, so the second long line
     * starts where the first one finished instead of paying for the same truncation again.
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
     * request builder guessing; {@link #translateBatch} is that caller.
     */
    public static JsonOutputMode jsonOutputMode() {
        return JsonOutputMode.PREFILL;
    }

    private static String requireModel(String model) {
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("Claude model is required");
        }
        return model.trim();
    }
}
