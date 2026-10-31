package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationStreamListener;
import org.universaltranslator.core.TranslationOutputValidator;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.HttpStatusException;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.net.ServerSentEvents;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small OpenAI-compatible chat provider used by local llama.cpp and optional hosted APIs. */
public final class OpenAiChatTranslationProvider implements TranslationProvider {
    /**
     * Smallest completion budget worth sending to the loopback server. Below this the model answers
     * with a character or two, and that fragment is still long enough to pass
     * {@link TranslationOutputValidator}, so it entered the persistent cache as a finished
     * translation.
     */
    private static final int MINIMUM_OFFLINE_COMPLETION_TOKENS = ChatBudget.MINIMUM_ANSWER_TOKENS;
    /** Upper bound for the completion budget of a hosted OpenAI-compatible endpoint. */
    private static final int MAXIMUM_TOKENS_LIMIT = 2048;
    /** Smallest completion budget a hosted endpoint gets, so a reasoning model can finish. */
    private static final int MINIMUM_COMPLETION_TOKENS = 512;
    /**
     * Completion budget for a model that spends its budget thinking before it writes the answer.
     * The 2048 ceiling is enough for a plain model, but a thinking model can exhaust it and return
     * only {@code reasoning_content} — which is exactly what "returned only reasoning content"
     * reports. A chain of thought for one short line runs into the thousands of tokens, so this is
     * set well above the 2048 the first attempt used. Raising the bound costs nothing for a model
     * that stops early, because {@code max_tokens} is an upper bound rather than a reservation.
     */
    private static final int REASONING_COMPLETION_TOKENS = 16384;
    /**
     * Declared ceiling for one batch reply.
     *
     * <p>A JSON reply repeats the whole translation plus a key and a separator per line, so sixteen
     * long lines need well over the 16384 a thinking model gets. Clamping there truncated the reply
     * at {@code finish_reason=length}, and the truncated JSON then failed to parse — one request plus
     * sixteen single-line repairs for every batch, deterministically, at {@code batch-size=16}. An
     * endpoint with a smaller output cap answers 400 and is retried at half of it (see
     * {@link #postBatchWithinBudget}); the value stays an upper bound rather than a reservation.
     */
    private static final int MAXIMUM_BATCH_COMPLETION_TOKENS = REASONING_COMPLETION_TOKENS * 2;
    /**
     * How long this endpoint is left off the streaming path after one stream failed inside its
     * budget.
     *
     * <p>Two dropped connections during a network handover were enough to lose the early preview for
     * the rest of the session, and the flag that did it had no way back. A cooldown keeps the cost of
     * rediscovery amortised while letting an endpoint recover on its own. The duration lives in
     * {@link StreamCompletionMemory} so all three streaming providers cool down identically.
     */
    private static final long STREAM_COOLDOWN_NANOS = StreamCompletionMemory.STREAM_COOLDOWN_NANOS;

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final String providerId;
    /**
     * The value {@link #id()} reports, computed once at construction.
     *
     * <p>It carries the endpoint and the credential header beside the provider name and the model,
     * because {@code TranslationCoordinator} keys both its in-flight map and the disk cache on it.
     * See {@link ProviderCacheKey} for what is hashed and what is deliberately left out.
     */
    private final String id;
    private final HttpJsonClient http;
    /**
     * Header the credential travels in, and the prefix its value gets. Every OpenAI-compatible
     * service uses {@code Authorization: Bearer ...}; Azure OpenAI uses a bare {@code api-key}
     * header instead, which is the only protocol difference this provider has to absorb.
     */
    private final String credentialHeader;
    private final String credentialPrefix;
    /**
     * Quality mode and optional custom system prompt. The default is the historical prompt, so a
     * provider built without this argument sends exactly the bytes it always sent.
     */
    private final TranslationPrompt.Settings prompt;
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
    /**
     * Set once this endpoint proved it cannot answer with an event stream, either by rejecting
     * {@code "stream": true} or by answering with an ordinary JSON document. Without this memory
     * every outgoing line would pay for the same discovery round trip before falling back, which is
     * exactly the cost that made streaming unsafe to enable by default.
     */
    private volatile boolean streamingUnsupported;
    /**
     * How this endpoint can be asked to answer the batch with a JSON object. The batch protocol keys
     * its reply by input line number, so an endpoint that can be told the format should be told
     * instead of being trusted to infer it from the prompt.
     */
    private final JsonOutputMode jsonOutput;
    /**
     * Set once an endpoint has answered that it does not know {@code response_format}. Without this
     * memory every later batch would pay for the same rejection before falling back to a reply that
     * ignores the request.
     */
    private volatile boolean jsonOutputRejected;
    /**
     * Earliest {@link System#nanoTime()} instant at which an event stream may be attempted again
     * after one failed inside its budget, or {@code 0} when nothing is cooling down.
     */
    private volatile long streamingRetryAtNanos;
    /** The escaped model-and-system-prompt half of a request body, built once per distinct prompt. */
    private volatile PromptBody promptBody;

    /**
     * How one group of texts is translated, handed to the gatherer.
     *
     * <p>The throughput settings ({@code batch-size} and {@code batch-window-ms}) are read once, by
     * {@link #gatherer}; they do not change the prompt, so they stay out of
     * {@link TranslationPrompt.Settings#signature()} and the cache key is unaffected by them.
     * {@code batch-size = 1} turns batching off completely.
     */
    private final BatchGatherer.Work work;
    /** Remembers an endpoint whose event stream never declares its own end. */
    private final StreamCompletionMemory streamMemory = new StreamCompletionMemory();
    /**
     * Largest completion budget one batch has been observed to accept, keyed by the number of texts
     * that batch carried and lowered only by an actual refusal.
     *
     * <p>Without it every batch of the session asked for the same value the endpoint had already
     * rejected: an endpoint capped at 8192 spent one refused request, one refused retry and then
     * sixteen single-line repairs on <em>every</em> batch, where the correct cost is one request.
     * The reduction loop that learns the value is {@link BatchBudget}, so the two providers that
     * gained a batch path later cannot rebuild the same defect on their own. See
     * {@link BatchBudget.ByEntries} for why the batch size is part of the key.
     */
    private final BatchBudget.ByEntries acceptedBatchTokens =
            new BatchBudget.ByEntries(MAXIMUM_BATCH_COMPLETION_TOKENS);
    /**
     * Largest number of texts one request has been observed to accept, lowered only by a refusal that
     * named the request's own length.
     *
     * <p>Nothing used to learn this: an endpoint whose context could not hold a batch of sixteen was
     * sent sixteen again on the next screen, and each attempt cost one refused request plus sixteen
     * repair requests. See {@link BatchBudget.ByBatchSize} — the value is package-private state of the
     * provider instance, so editing the settings starts from the configured {@code batch-size} again.
     */
    private final BatchBudget.ByBatchSize acceptedBatchSizes;

    /**
     * Groups nearby texts into one request, and decides who waits for whom.
     *
     * <p>The queue, the per-job single-flight role and the waiters' deadline all live in
     * {@link BatchGatherer}; this provider only says how one group is translated.
     */
    private final BatchGatherer gatherer;

    public OpenAiChatTranslationProvider(String endpoint, String apiKey, String model, String providerId) {
        this(endpoint, apiKey, model, providerId, new HttpJsonClient(5000, 120000));
    }

    public OpenAiChatTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            String providerId,
            TranslationPrompt.Settings prompt
    ) {
        this(endpoint, apiKey, model, providerId, new HttpJsonClient(5000, 120000), prompt);
    }

    OpenAiChatTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            String providerId,
            HttpJsonClient http
    ) {
        this(endpoint, apiKey, model, providerId, http, TranslationPrompt.Settings.standard());
    }

    OpenAiChatTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            String providerId,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt
    ) {
        this(endpoint, apiKey, model, providerId, http, prompt, "Authorization", "Bearer ");
    }

    OpenAiChatTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            String providerId,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt,
            TranslationTuning tuning
    ) {
        this(endpoint, apiKey, model, providerId, http, prompt, "Authorization", "Bearer ",
                JsonOutputMode.RESPONSE_FORMAT, tuning);
    }

    OpenAiChatTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            String providerId,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt,
            String credentialHeader,
            String credentialPrefix
    ) {
        this(endpoint, apiKey, model, providerId, http, prompt, credentialHeader, credentialPrefix,
                JsonOutputMode.RESPONSE_FORMAT);
    }

    /**
     * The loopback llama.cpp endpoint: it answers plain text and never takes part in a batch, so it
     * declares that it cannot be asked for a JSON object.
     */
    OpenAiChatTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            String providerId,
            HttpJsonClient http,
            JsonOutputMode jsonOutput
    ) {
        this(endpoint, apiKey, model, providerId, http, TranslationPrompt.Settings.standard(),
                "Authorization", "Bearer ", jsonOutput);
    }

    OpenAiChatTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            String providerId,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt,
            String credentialHeader,
            String credentialPrefix,
            JsonOutputMode jsonOutput
    ) {
        this(endpoint, apiKey, model, providerId, http, prompt, credentialHeader, credentialPrefix,
                jsonOutput, TranslationTuning.DEFAULTS);
    }

    OpenAiChatTranslationProvider(
            String endpoint,
            String apiKey,
            String model,
            String providerId,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt,
            String credentialHeader,
            String credentialPrefix,
            JsonOutputMode jsonOutput,
            TranslationTuning tuning
    ) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(normalizeEndpoint(endpoint));
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = requireText("model", model);
        this.providerId = requireText("providerId", providerId);
        this.http = http;
        this.prompt = prompt == null ? TranslationPrompt.Settings.standard() : prompt;
        this.credentialHeader = credentialHeader == null || credentialHeader.trim().isEmpty()
                ? "Authorization" : credentialHeader.trim();
        this.credentialPrefix = credentialPrefix == null ? "" : credentialPrefix;
        // An unspecified mode means "do not send a field this endpoint was never asked about".
        this.jsonOutput = jsonOutput == null ? JsonOutputMode.NONE : jsonOutput;
        TranslationTuning effectiveTuning = tuning == null ? TranslationTuning.DEFAULTS : tuning;
        this.acceptedBatchSizes = new BatchBudget.ByBatchSize(effectiveTuning.batchSize());
        this.gatherer = new BatchGatherer(effectiveTuning.batchSize(),
                effectiveTuning.batchWindowMillis(),
                effectiveTuning.maximumConcurrentBatches(),
                effectiveTuning.requestIntervalMillis());
        // OnlineProviderConfig puts every provider it builds inside ResilientTranslationProvider,
        // which retries the very failures the gatherer would resend a group for.
        this.gatherer.setOuterRetries(true);
        // The cache key has to name the endpoint and the header the credential travels in, not just
        // the model: two hosts that serve the same model name are not the same engine, and before
        // this the second one was never asked. Only the address and the header *name* are hashed, so
        // rotating a key does not throw the cache away.
        this.id = ProviderCacheKey.of(this.providerId, this.model, this.endpoint,
                this.credentialHeader + "=" + this.credentialPrefix, this.prompt.signature());
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
     * The credential as the header map this endpoint expects, or an empty map when no key is set
     * (a local llama.cpp or Ollama server needs none).
     */
    private Map<String, String> credentialHeaders() {
        return apiKey.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.singletonMap(credentialHeader, credentialPrefix + apiKey);
    }

    /**
     * Accepts the base-URL form OpenAI-compatible relays advertise.
     *
     * <p>{@code https://host/v1} is what those services print as their API address, and posting a
     * chat request to it answers with the relay's HTML page while {@code /v1/models} still works —
     * a service that looks healthy and never translates.
     *
     * <p>The path is compared, not the whole string: matching only the exact {@code …/v1} and
     * {@code …/v1/} tails left every other spelling of the same address broken. A trailing slash
     * after the chat path
     * ({@code https://host/v1/chat/completions/}, which much of the documentation writes) answered
     * 404, and a query string made the tail test fail, so
     * {@code https://host/v1?api-version=…} was sent to {@code /v1} and answered the relay's HTML
     * page again — the same symptom this method exists to remove, reported against an address the
     * user copied from the provider.
     *
     * @param endpoint configured chat-completions endpoint
     * @return the endpoint to request, with the chat path filled in when only a base URL was given
     */
    static String normalizeEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        String path = value;
        String query = "";
        int queryStart = value.indexOf('?');
        if (queryStart >= 0) {
            path = value.substring(0, queryStart);
            query = value.substring(queryStart);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith("/chat/completions")) {
            return path + query;
        }
        if (path.endsWith("/v1")) {
            return path + "/chat/completions" + query;
        }
        return value;
    }

    /**
     * The provider id: {@code providerId:model@endpointDigest[#promptSignature]}.
     *
     * <p>{@link org.universaltranslator.core.TranslationCoordinator} builds both its in-flight key
     * and its persistent-cache key from this value, so it has to name the endpoint as well as the
     * model — {@code openai-compatible} reports one fixed provider name whatever
     * {@code llm-api-endpoint} holds, and pointing the same model name at another host used to keep
     * the old id and replay the previous server's translation from the disk cache. The prompt
     * signature keeps a quality mode or a custom prompt in entries of their own. See
     * {@link ProviderCacheKey}: only the address and the credential header's name are hashed, so no
     * key material reaches this string and rotating a key keeps the cache.
     */
    @Override
    public String id() {
        return id;
    }

    /** Test seam: widens the collection window so a batching test is not timing dependent. */
    void setBatchWindowMillis(long millis) {
        gatherer.setWindowMillis(millis);
    }

    /**
     * Translates one text, batching texts that arrive close together.
     *
     * <p>A thinking model spends seconds on every request, so one request carrying several lines is
     * the difference between a scoreboard that fills in at once and one that trickles in line by
     * line. A text containing line breaks now batches as well: the batch protocol is a JSON object
     * keyed by input line number and a value may carry {@code \n}, so a multi-line text no longer
     * has to fall back to a request of its own. The offline loopback server still does: its context
     * window is 1024 tokens and it serves one request at a time.
     *
     * <p>{@link BatchGatherer} owns the queue and the per-job single-flight role, so two workers with
     * the same target language share one request while a second language no longer waits for them.
     */
    @Override
    public String translate(TranslationRequest request) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            // A closed session must not pay for one more request, and nothing waits for the answer
            // any more: the coordinator has already settled every open request when it shuts the
            // workers down. Without this the group below is still sent, and the batch path has no
            // other place to notice the cancellation.
            throw new InterruptedIOException("translation cancelled");
        }
        // The offline loopback server is single threaded and its context window is 1024 tokens, so
        // it never batches; and a configured batch size of 1 asks for exactly the same thing.
        if (providerId.startsWith("offline-loopback") || !gatherer.batching()) {
            return translateOne(request);
        }
        return gatherer.translate(request, work);
    }

    /** Translates exactly one text with one request. */
    private String translateOne(TranslationRequest request) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            // A closed session must not keep sending one paid request per remaining line; the
            // gatherer's own guard only covers the lines it repairs after a reply.
            throw new InterruptedIOException("translation cancelled");
        }
        String system = systemPrompt(request);
        boolean offline = providerId.startsWith("offline-loopback");
        int maximumTokens = completionBudget(request.getText(), offline);
        DebugLog.global().logRequest(providerId, model, endpoint.toString(),
                request.getKind().name(), request.getText());
        String response = post(system, request.getText(), maximumTokens, offline);
        Object root = JsonTree.parseOrNull(response);
        String translated = extractContent(root, response);
        if ((translated == null || translated.trim().isEmpty()) && !offline
                && reasoningContent(root, "message") != null) {
            // The model thought until the budget ran out and never wrote the translation; the
            // answer only appears once the completion is allowed to finish. Remember the endpoint
            // reasons, so the next line does not repeat the smaller first attempt.
            reasoningModel = true;
            int escalatedBudget = reasoningBudget();
            if (escalatedBudget > maximumTokens) {
                response = postWithAcceptedBudget(system, request.getText(), escalatedBudget, offline);
                maximumTokens = escalatedBudget;
                root = JsonTree.parseOrNull(response);
                translated = extractContent(root, response);
            } else {
                // The accepted budget is already what this request would have used, so raising it is
                // not possible and every later line would fail the same way. Say what is wrong
                // instead of reporting the generic "returned only reasoning content".
                throw new IllegalStateException("This endpoint accepts at most " + escalatedBudget
                        + " output tokens, and the thinking model uses them up before it writes an"
                        + " answer; pick a model that does not reason first, or raise the endpoint's"
                        + " output limit");
            }
        }
        // An answer the endpoint itself reported as unfinished is the first half of the translation,
        // not a short translation of a long text: the output validator bounds an answer from above
        // but has no floor a half sentence would miss, and whatever it accepts is written into the
        // persistent cache, so one truncated answer would be replayed for the rest of the
        // installation. Ask once with the larger budget the thinking-model path already uses, and
        // refuse the line when even that comes back cut.
        String abnormal = abnormalFinishReason(JsonTree.stringAt(root, "choices[0].finish_reason"));
        if (abnormal != null && !offline && reasoningBudget() > maximumTokens) {
            response = postWithAcceptedBudget(system, request.getText(), reasoningBudget(), offline);
            root = JsonTree.parseOrNull(response);
            translated = extractContent(root, response);
            abnormal = abnormalFinishReason(JsonTree.stringAt(root, "choices[0].finish_reason"));
            // Quote the budget the request really used: the endpoint may have halved it on a refusal.
            maximumTokens = reasoningBudget();
        }
        if (translated == null || translated.trim().isEmpty()) {
            throw missingContent(root, response);
        }
        if (abnormal != null) {
            throw new IllegalStateException("This endpoint cut the answer off at its output limit ("
                    + "finish_reason: " + abnormal + ", " + maximumTokens + " tokens asked for);"
                    + " shorten the text or raise the endpoint's output limit");
        }
        // Usage and the trace describe a translation, so they are recorded for one that passed the
        // output validator: a rejected answer used to be counted as tokens spent and as an answer.
        String validated = TranslationOutputValidator.requireValid(request.getText(), translated);
        recordUsage(root);
        DebugLog.global().logResponse(providerId, request.getKind().name(), translated);
        return validated;
    }

    /**
     * Reads one reasoning field of a chat-completion document.
     *
     * <p>Only the two paths a chat completion can put it on are consulted. Scanning the whole body for
     * the string was wrong twice: a relay that echoes the request carries the field on the request
     * side, so every line looked like "only reasoning content" and paid for a second request with the
     * larger budget, and the scan itself walked the whole body per line.
     *
     * @param root parsed document, or {@code null}
     * @param container {@code message} for a complete reply, {@code delta} for one stream event
     * @return the reasoning text, or {@code null} when the document carries none
     */
    private static String reasoningContent(Object root, String container) {
        return JsonTree.stringAt(root, "choices[0]." + container + ".reasoning_content");
    }

    /**
     * The failure for an answer that carried no translation.
     *
     * <p>A body that is not JSON at all is a transport-level surprise — an HTML error page from a
     * captive portal or an intercepting proxy, a truncated reply — so it is reported as an
     * {@link IOException} that the retry and fallback layers may act on. {@code IllegalArgumentException}
     * and {@code IllegalStateException} mean "this request cannot succeed here", which is the wrong
     * verdict for a body the endpoint mangled once.
     *
     * @param root parsed reply, or {@code null} when the body was not JSON
     * @param response raw response body
     * @return the failure to throw
     */
    private static Exception missingContent(Object root, String response) {
        String description = describeMissingContent(root, response);
        return root == null ? (Exception) new IOException(description)
                : new IllegalStateException(description);
    }

    /**
     * Adds the token counts this endpoint reported to the process-wide statistics.
     *
     * <p>Only the ordinary response body carries {@code usage}; the event stream of a streamed
     * request does not, so a streamed line contributes to the request and latency counters but not
     * to the token totals. The parsed document is handed in rather than the body, because both
     * callers already parsed it to read the translation: giving the text back made every request
     * pay for a second full parse of a multi-kilobyte answer.
     */
    private void recordUsage(Object root) {
        if (root instanceof Map && ((Map<?, ?>) root).containsKey("usage")) {
            TranslationStats.global().recordParsedUsage(providerId, root);
        }
    }

    /**
     * Translates one text while reporting it as it is generated.
     *
     * <p>Only the outgoing-chat path asks for this: it is the one request whose latency the player
     * waits on with the chat box already closed, and it is always a single line.
     *
     * <p>Every case that could make streaming behave differently from the plain path is refused up
     * front, so the feature can only ever add an early preview:
     *
     * <ul>
     *   <li>the offline loopback server serves one request at a time out of a 1024-token context and
     *       has no streaming form here, so it keeps the existing path;
     *   <li>a multi-line text is carried by the numbered batch format, which a stream cannot replace;
     *   <li>an endpoint that has already proved it cannot stream is never asked again.
     * </ul>
     *
     * <p>When the stream produces nothing usable the plain path takes over — it is also the path that
     * explains why — so the result stays identical to the non-streaming behaviour on every endpoint
     * that does not stream, and finding that out costs one extra request, once per endpoint.
     */
    @Override
    public String translateStreaming(TranslationRequest request, TranslationStreamListener listener)
            throws Exception {
        if (listener == null) {
            // Nothing to publish, so the plain path is both cheaper and exactly what was asked for.
            // Falling through to the default method would dereference the null listener instead.
            return translate(request);
        }
        if (streamingUnsupported
                || streamingCoolingDown()
                || providerId.startsWith("offline-loopback")
                || request.getText().indexOf('\n') >= 0) {
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        String text = request.getText();
        String system = systemPrompt(request);
        // The same budget the plain single-text path would ask for, so an endpoint that ignores
        // "stream" cannot end up producing a different answer here than it gives there.
        int maximumTokens = completionBudget(text, false);
        StreamAttempt attempt = postStreamingAttempt(system, text, maximumTokens, listener);
        if (attempt.unsupported) {
            streamingUnsupported = true;
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        if (attempt.content == null && attempt.sawReasoning) {
            // A thinking model streamed only its chain of thought and never wrote the answer inside
            // the smaller budget. The plain path already knows how to repair exactly that: once the
            // endpoint is known to reason it starts from the larger completion budget. Remember it
            // and let that path do the work instead of reporting a failure.
            reasoningModel = true;
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        if (attempt.content == null && !attempt.complete) {
            // The stream stopped early, hit one of the watchdog's budgets, or carried a relay error
            // object. A partial text is never shown as the answer: only a stream that reached its own
            // end marker becomes attempt.content, so a connection that died mid-answer is refused
            // here instead of being cached as a finished translation. The plain path is the one that
            // recovers from all of those, and its result is what callers have always received.
            //
            // The trace matters because that recovery is a second, complete request: an endpoint
            // that never declares its own end turns every line into two paid calls. Recording it is
            // not enough, though, so a streak of these takes this endpoint off the streaming path for
            // a while — that is what stops the doubling from repeating for the rest of the session,
            // and the cooldown is what lets the endpoint come back.
            DebugLog.global().logStreamEvent(providerId, "incomplete", 0);
            if (streamMemory.rememberIncomplete()) {
                streamingRetryAtNanos = System.nanoTime() + STREAM_COOLDOWN_NANOS;
                DebugLog.global().logStreamEvent(providerId, "streaming-abandoned", 0);
            }
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        if (attempt.content == null) {
            // The stream declared its own end and carried no text at all. That is a legal, empty
            // answer — a block reason, a refused prompt — and {@link StreamCompletionMemory} says an
            // empty answer must never be counted as an incomplete stream: counting it disabled
            // streaming on an endpoint that merely answered "nothing to translate".
            DebugLog.global().logStreamEvent(providerId, "empty", 0);
            return TranslationProvider.super.translateStreaming(request, listener);
        }
        streamMemory.rememberComplete();
        return TranslationOutputValidator.requireValid(text, attempt.content);
    }

    /** Whether a recent stream failure still keeps this endpoint off the streaming path. */
    private boolean streamingCoolingDown() {
        return StreamCompletionMemory.coolingDown(streamingRetryAtNanos);
    }

    /** What one streaming request produced, and whether the endpoint can stream at all. */
    private static final class StreamAttempt {
        private String content;
        /**
         * Set only by an explicit end marker — the {@code [DONE]} sentinel or a normal
         * {@code finish_reason}, because not every relay sends the sentinel. Without one there is no
         * way to tell a finished answer from a connection that died after a few chunks: both arrive
         * as a prefix of the translation.
         */
        private boolean complete;
        private boolean sawReasoning;
        private boolean unsupported;
    }

    /**
     * Sends one request with {@code "stream": true} and assembles the translation from its events.
     *
     * <p>Returns as soon as the endpoint shows it cannot stream, so the caller can fall back without
     * waiting for a body that was never going to be an event stream.
     */
    private StreamAttempt postStreamingAttempt(
            String system,
            String text,
            int maximumTokens,
            TranslationStreamListener listener
    ) throws Exception {
        StreamAttempt attempt = new StreamAttempt();
        String body = requestBody(system, text, maximumTokens, false, true);
        StringBuilder accumulated = new StringBuilder(64);
        // Publishing the whole accumulation on every delta made one translation copy O(n^2)
        // characters — a 1000-character answer behind 1000 deltas is half a megabyte of throwaway
        // strings on the path the player waits on. The threshold, and the closing publish that makes
        // the last preview complete, both live in the publisher so the three streaming providers
        // cannot drift apart.
        ProviderSupport.PreviewPublisher preview = new ProviderSupport.PreviewPublisher(listener);
        try (HttpJsonClient.StreamedResponse response =
                     http.postStreaming(endpoint, body, credentialHeaders())) {
            if (!EventStream.isEventStream(response.getContentType())) {
                // A relay that ignores "stream" answers with an ordinary JSON document. Its body is
                // deliberately not read here: the plain path re-sends the same request and knows how
                // to read, report and recover from it.
                attempt.unsupported = true;
                return attempt;
            }
            InputStream streamBody = response.getBody();
            if (streamBody == null) {
                // Nothing to read, and the parser would throw a NullPointerException on its first
                // read; the plain path is the one that explains a bodyless response.
                attempt.unsupported = true;
                return attempt;
            }
            try {
                // One budget for one stream: the instant {@code HttpJsonClient} already computed for
                // this response, which {@code api-read-timeout-ms} decides, capped by the ceiling
                // every streaming provider shares — without it one setting meant five minutes here
                // and twenty on this path, which is the same disagreement in the other direction.
                long deadlineNanos = response.getDeadlineNanos();
                long progressBudgetMillis = ProviderSupport.stallBudgetMillis(response);
                // A keep-alive-only stream, or a session that is being closed, has to be able to end
                // the read: the parser stops for silence and for its payload bounds, and only this
                // wrapper can turn an interrupt into a failure while nothing is arriving.
                ServerSentEvents.read(
                        StreamWatchdog.arm(streamBody, progressBudgetMillis),
                        payload -> readEvent(providerId, payload, accumulated, attempt, preview),
                        deadlineNanos);
            } catch (IOException streamFailure) {
                // A cancelled session is not a dropped body. Escaping here sends the plain path one
                // more complete request after the worker was already asked to stop, and no one waits
                // for the answer: the coordinator already settled the request. A socket read timeout
                // is also an InterruptedIOException but is a transport failure, so it keeps the
                // read-what-arrived behaviour below.
                if (streamFailure instanceof InterruptedIOException
                        && !(streamFailure instanceof SocketTimeoutException)) {
                    throw streamFailure;
                }
                // A body that stopped arriving, or one that exceeded a byte bound, is not a rejection
                // of the request: escaping here skipped the memory below, so every later line paid the
                // same three attempts again. Reading whatever arrived and reporting "incomplete" is
                // what the retry and cooldown decisions are made from.
                DebugLog.global().logStreamEvent(providerId, "stream-abandoned", 0);
            }
        } catch (HttpStatusException rejected) {
            if (rejected.isRetryable()) {
                // A rate limit or a server error is transient. Retrying it is the resilient
                // provider's job, and it must not be mistaken for "this endpoint cannot stream".
                throw rejected;
            }
            attempt.unsupported = true;
            return attempt;
        }
        // Only a stream that reached its own end marker may become the answer. An endpoint, a relay
        // or a mobile network dropping the connection mid-answer leaves a half translation that is
        // still long enough to pass the validator, so without this test it would be written to the
        // persistent cache as a finished translation.
        if (attempt.complete && accumulated.length() > 0) {
            // The throttle may have held the tail back; the preview has to end up complete.
            preview.finish(accumulated);
            attempt.content = accumulated.toString();
        }
        return attempt;
    }

    /**
     * Handles one event. Only {@code delta.content} is translation: {@code delta.reasoning_content}
     * is the model's chain of thought and must never be shown as the answer.
     *
     * @param preview hands the accumulated answer to the listener once it grew by a step
     * @return {@code false} to stop reading, after the sentinel or a relay-side error
     */
    private static boolean readEvent(
            String provider,
            String payload,
            StringBuilder accumulated,
            StreamAttempt attempt,
            ProviderSupport.PreviewPublisher preview
    ) {
        if ("[DONE]".equals(payload.trim())) {
            // The sentinel is the unambiguous end: it needs no reading of a finish reason, and a
            // connection that simply goes silent is the opposite of what it looks like.
            attempt.complete = true;
            DebugLog.global().logStreamEvent(provider, "done", 0);
            return false;
        }
        // One parse per event: the content, the reasoning flag, the finish reason and the error test
        // are all fields of the same document, and parsing it once per field made every delta cost
        // four object trees on the worker that is assembling the answer.
        Object root = JsonTree.parseOrNull(payload);
        String delta = JsonTree.stringAt(root, "choices[0].delta.content");
        if (delta == null) {
            delta = JsonTree.stringAt(root, "choices[0].message.content");
        }
        if (delta != null && !delta.isEmpty()) {
            accumulated.append(delta);
            DebugLog.global().logStreamEvent(provider, "delta", delta.length());
            // One callback per PARTIAL_TEXT_STEP_CHARS instead of one per delta: every callback used
            // to copy the whole accumulation, which is quadratic in the length of the answer.
            preview.append(accumulated);
            // A relay may put the finish reason on the very chunk that carries the last text, so the
            // completion test runs here as well instead of only on the events that follow.
            markFinished(attempt, root);
            // A finished answer must not keep the read — and the worker — alive until a sentinel this
            // relay never sends. Returning true here let the watchdog throw away a complete
            // translation at the end of its budget.
            return !attempt.complete;
        }
        if (reasoningContent(root, "delta") != null) {
            attempt.sawReasoning = true;
            DebugLog.global().logStreamEvent(provider, "reasoning", 0);
            // A thinking model may carry its finish reason on the last chunk it reasons in, so the same
            // "the endpoint already said it is done" test applies to this branch as well.
            markFinished(attempt, root);
            return !attempt.complete;
        }
        markFinished(attempt, root);
        if (isRelayError(root)) {
            // Relays report a failure as an ordinary event behind HTTP 200. Stop reading and let the
            // plain path produce the message the user has always seen.
            DebugLog.global().logStreamEvent(provider, "error-event", 0);
            return false;
        }
        // A closing chunk that carries no text is how the relays named in {@link #markFinished} end
        // an answer: returning true here kept the read — and the worker — waiting for a sentinel
        // those endpoints never send, until the read timeout threw a complete translation away.
        return !attempt.complete;
    }

    /**
     * The finish reason that means the answer was cut short, or {@code null} when it ended normally.
     *
     * <p>The event stream already refused both of these, and the single-text path used to ignore the
     * field altogether, so the one decision — did the model finish — had two answers and the one
     * missing it fed truncated answers to the output validator, which has no floor. An absent or
     * empty reason counts as a normal end, because an endpoint is free not to report one.
     *
     * @param finishReason value read from {@code choices[0].finish_reason}, or {@code null}
     * @return {@code length} or {@code content_filter}, otherwise {@code null}
     */
    private static String abnormalFinishReason(String finishReason) {
        if (finishReason == null) {
            return null;
        }
        String reason = finishReason.trim();
        if ("length".equals(reason) || "content_filter".equals(reason)) {
            return reason;
        }
        return null;
    }

    /**
     * Marks the attempt complete when the event names a normal finish.
     *
     * <p>Not every relay sends the {@code [DONE]} sentinel: Azure OpenAI and several self-hosted
     * gateways put {@code finish_reason} on their last chunk instead. Accepting only the sentinel
     * made every line of those endpoints look incomplete, and each one then paid for a second,
     * complete request.
     *
     * <p>Only a normal stop counts. {@code length} means the completion budget ran out mid-sentence
     * and {@code content_filter} means the provider cut the answer, so both are exactly the
     * truncated results this completeness test exists to reject — see
     * {@link #abnormalFinishReason}, which the single-text path reads the same value through.
     *
     * @param attempt attempt to mark
     * @param root one parsed event payload
     */
    private static void markFinished(StreamAttempt attempt, Object root) {
        String finishReason = JsonTree.stringAt(root, "choices[0].finish_reason");
        if (finishReason == null) {
            return;
        }
        String reason = finishReason.trim();
        if (reason.isEmpty() || abnormalFinishReason(reason) != null) {
            return;
        }
        attempt.complete = true;
    }

    /**
     * Whether one event is the relay's own error object rather than a chat-completion chunk.
     *
     * <p>The test has to name the event an error explicitly. Treating any event that carries a
     * {@code message} field as a failure stopped the read on endpoints that send progress events
     * such as {@code {"type":"ping","message":"keep-alive"}}, which clipped a perfectly healthy
     * stream at the first keep-alive. Every field is read from the one parsed event: rescanning the
     * raw payload for a field name cost a second and third walk over the same text on every delta,
     * and it matched request-side fields a relay echoes back.
     *
     * @param root one parsed event payload
     * @return {@code true} when the payload is a relay-side error object
     */
    private static boolean isRelayError(Object root) {
        if ("error".equals(JsonTree.stringAt(root, "type"))) {
            return true;
        }
        if (JsonTree.stringAt(root, "error") != null) {
            return true;
        }
        return JsonTree.stringAt(root, "error.message") != null
                || JsonTree.stringAt(root, "error.type") != null;
    }

    /**
     * The system prompt of the single-text path; the batch path builds its own.
     *
     * <p>The wording lives in {@link TranslationPrompt} so the quality mode and any custom prompt
     * are applied in exactly one place.
     */
    private String systemPrompt(TranslationRequest request) {
        return TranslationPrompt.single(prompt, request);
    }

    /**
     * Completion budget for one text.
     *
     * <p>A fixed 512-token cap truncated long lines mid-sentence, and the clipped result was still
     * short enough to pass {@link TranslationOutputValidator}, so it entered the persistent cache —
     * the budget therefore scales with the input.
     */
    private int completionBudget(String text, boolean offline) {
        int requestedTokens = ChatBudget.completionBudget(text, MAXIMUM_TOKENS_LIMIT,
                MINIMUM_COMPLETION_TOKENS);
        if (offline) {
            // The loopback llama.cpp server runs --ctx-size 1024 and shares that window
            // between prompt and completion. Cap the completion to what is left after a
            // conservative prompt estimate, with no floor that could overflow the context.
            int promptTokens = ChatBudget.estimatePromptTokens(text);
            int remaining = ChatBudget.remainingContext(LoopbackContext.TOKENS, text);
            if (remaining < MINIMUM_OFFLINE_COMPLETION_TOKENS) {
                // Sending the remainder anyway asked for one or two tokens, the model answered with a
                // character or two, and that fragment passed the output validator and was cached
                // permanently as the translation of the whole text. A text that does not fit the
                // context window has no answer here, so it is reported instead of half-translated.
                // The window is a constant of the loopback server, so the message offers an action
                // the user actually has instead of one that needs a rebuild.
                throw new IllegalArgumentException("Text needs about " + promptTokens
                        + " prompt tokens of the " + LoopbackContext.TOKENS
                        + "-token offline context, which leaves " + Math.max(0, remaining)
                        + " for the translation; shorten this text, or use an online provider for it");
            }
            return Math.min(Math.max(MINIMUM_OFFLINE_COMPLETION_TOKENS, requestedTokens), remaining);
        }
        if (reasoningModel) {
            // This endpoint already proved it thinks before it answers, so start with the larger
            // budget instead of spending a round trip rediscovering that on every single line.
            return reasoningBudget();
        }
        // Reasoning models spend completion tokens thinking before they write the answer, so
        // even a short input must leave room to finish. max_tokens is an upper bound rather
        // than a reservation, so this costs nothing for models that stop early.
        return Math.max(MINIMUM_COMPLETION_TOKENS, requestedTokens);
    }

    /** Completion budget for an endpoint that has proved it thinks before it answers. */
    private int reasoningBudget() {
        return Math.max(MINIMUM_COMPLETION_TOKENS,
                Math.min(REASONING_COMPLETION_TOKENS, acceptedReasoningTokens));
    }

    /**
     * Sends every text of one group as one JSON object in a single request.
     *
     * <p>The request is a JSON object as well: the key is the line number and the value is the text,
     * so a text carrying a line break, a quote or a backslash travels as one unambiguous entry and
     * the model can answer every entry by key.
     *
     * @return one entry per group member in order, with {@code null} for a line the reply did not
     *         answer; {@code null} when the request failed or the reply carried no batch at all
     */
    private List<String> translateBatch(List<TranslationRequest> group) throws Exception {
        String user = ProviderSupport.batchUserBody(group);
        String system = TranslationPrompt.batch(prompt, group.get(0));
        int maximumTokens = batchBudget(user, group.size());
        if (reasoningModel) {
            maximumTokens = Math.max(maximumTokens,
                    Math.min(REASONING_COMPLETION_TOKENS, acceptedReasoningTokens));
        }
        // The trace carries the texts, not just their size: a batch is the only path where the model
        // can answer the wrong line, and the eight previews are what makes that diagnosable. The
        // preview is bounded by the log itself, so the privacy cost is the one already paid.
        if (DebugLog.global().isEnabled()) {
            DebugLog.global().logRequest(providerId, model, endpoint.toString(),
                    group.get(0).getKind().name(), batchPreview(group));
        }
        String response = postBatchWithinBudget(group, system, user, maximumTokens);
        Object root = JsonTree.parseOrNull(response);
        List<String> parsed = parseBatch(root, response, group.size());
        if (parsed == null && maximumTokens < REASONING_COMPLETION_TOKENS
                && reasoningContent(root, "message") != null) {
            reasoningModel = true;
            int reasoningBudget = Math.min(REASONING_COMPLETION_TOKENS, acceptedReasoningTokens);
            if (reasoningBudget > maximumTokens) {
                response = postBatchWithinBudget(group, system, user, reasoningBudget);
                root = JsonTree.parseOrNull(response);
                parsed = parseBatch(root, response, group.size());
            }
        }
        if (parsed == null) {
            // A reply that is not one JSON object is the third shape of "this batch was too large": the
            // model ran out of room before closing the object. Nothing remembered that, so the next
            // screen asked for the same number of entries and paid for the same failure again.
            gatherer.setBatchSize(acceptedBatchSizes.rememberTooLarge(group.size()));
            throw missingContent(root, response);
        }
        recordUsage(root);
        DebugLog.global().logResponse(providerId, group.get(0).getKind().name(),
                parsed.size() + " entries");
        return parsed;
    }

    /** The texts one batch carries, joined for the trace; {@code DebugLog} bounds the length. */
    private static String batchPreview(List<TranslationRequest> group) {
        StringBuilder preview = new StringBuilder(96);
        for (int index = 0; index < group.size(); index++) {
            if (index > 0) {
                preview.append(" | ");
            }
            preview.append(group.get(index).getText());
        }
        return preview.toString();
    }

    /**
     * Completion budget for one batch, capped by whatever this endpoint already accepted.
     *
     * <p>The reply is a JSON object, so on top of the translations it carries a key, a pair of
     * quotes and a separator per entry, and every line break inside a translation is written as two
     * characters. The estimate itself lives in {@link ChatBudget} and counts prompt
     * <em>tokens</em>: sizing a batch from the request's JSON character count overestimated by about
     * four times on ASCII, so every batch asked for the ceiling and an endpoint with a smaller output
     * cap had to refuse it — on every batch of the session, because nothing remembered the refusal.
     *
     * @param requestBody the JSON request that was built
     * @param entries number of texts in the batch
     * @return completion tokens to ask for
     */
    private int batchBudget(String requestBody, int entries) {
        int requested = ChatBudget.batchCompletionBudget(requestBody, entries,
                MAXIMUM_BATCH_COMPLETION_TOKENS, MINIMUM_COMPLETION_TOKENS);
        return Math.min(requested, acceptedBatchTokens.forEntries(entries).budget());
    }

    /**
     * Reads one batch reply into one translation per input line.
     *
     * <p>The JSON object is tried first. A reply that carries no usable JSON falls back to the
     * numbered list the protocol used before JSON, which is what an endpoint that ignores the
     * instruction produces — the small local models above all.
     *
     * @param root the same body, already parsed
     * @param response raw response body
     * @param expected number of texts in the batch
     * @return one entry per expected line with {@code null} where the reply had nothing usable, or
     *         {@code null} when the reply carries no batch at all
     */
    private List<String> parseBatch(Object root, String response, int expected) {
        String content = extractContent(root, response);
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        List<String> fromJson = parseJsonBatch(content, expected);
        if (hasEntries(fromJson)) {
            return fromJson;
        }
        List<String> fromNumbering = parseNumberedBatch(content, expected);
        if (hasEntries(fromNumbering)) {
            return fromNumbering;
        }
        // A JSON object that answered nothing is still a batch reply: report its holes so each line
        // is repaired on its own instead of claiming the whole reply was unusable.
        return fromJson != null ? fromJson : fromNumbering;
    }

    /**
     * Reads a JSON object keyed by input line number.
     *
     * <p>Only the first opening brace to the last closing brace is considered, so a model that
     * introduces or explains its answer still gets read. A key that is not an input line number and a
     * value that is not a string are ignored rather than rejected: an extra entry costs nothing, and
     * the line it failed to answer is retried on its own.
     *
     * @param content assistant message text
     * @param expected number of texts in the batch
     * @return one entry per expected line, {@code null} where the object had nothing usable, or
     *         {@code null} when the content carries no JSON object at all
     */
    static List<String> parseJsonBatch(String content, int expected) {
        if (content == null) {
            return null;
        }
        int first = content.indexOf('{');
        int last = content.lastIndexOf('}');
        if (first < 0 || last <= first) {
            return null;
        }
        Object parsed;
        try {
            parsed = JsonStrings.parse(content.substring(first, last + 1));
        } catch (RuntimeException malformed) {
            return null;
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        List<String> results = emptyResults(expected);
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
            int line = lineNumberOf(entry.getKey());
            if (line < 1 || line > expected || !(entry.getValue() instanceof String)) {
                continue;
            }
            String value = ((String) entry.getValue()).trim();
            if (!value.isEmpty() && results.get(line - 1) == null) {
                results.set(line - 1, value);
            }
        }
        return results;
    }

    /**
     * Reads the numbered list the batch protocol used before JSON.
     *
     * <p>Deliberately stricter than the JSON parser: a line that does not begin with a number means
     * the reply is prose, and attaching the rest of it to input lines could put a translation on the
     * wrong line, so the whole reply is refused instead. A number that simply never appears leaves a
     * hole, which costs one request for that line rather than a resend of the batch.
     *
     * @param content assistant message text
     * @param expected number of texts in the batch
     * @return one entry per expected line, {@code null} for a number the list did not carry, or
     *         {@code null} when the content is not a numbered list at all
     */
    static List<String> parseNumberedBatch(String content, int expected) {
        if (content == null) {
            return null;
        }
        List<String> results = emptyResults(expected);
        boolean sawAny = false;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = -1;
            for (int index = 0; index < trimmed.length(); index++) {
                char character = trimmed.charAt(index);
                if (character < '0' || character > '9') {
                    separator = index;
                    break;
                }
            }
            if (separator <= 0) {
                return null;
            }
            int number;
            try {
                number = Integer.parseInt(trimmed.substring(0, separator));
            } catch (NumberFormatException notNumbered) {
                return null;
            }
            if (number < 1 || number > expected || results.get(number - 1) != null) {
                return null;
            }
            String value = trimmed.substring(separator);
            while (value.startsWith(".") || value.startsWith(")") || value.startsWith(":")
                    || value.startsWith("\u3001") || value.startsWith(" ") || value.startsWith("\u3000")) {
                value = value.substring(1);
            }
            if (value.trim().isEmpty()) {
                continue;
            }
            results.set(number - 1, value);
            sawAny = true;
        }
        return sawAny ? results : null;
    }

    /** Whether a parsed reply actually answered at least one line. */
    private static boolean hasEntries(List<String> results) {
        if (results == null) {
            return false;
        }
        for (String value : results) {
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    private static List<String> emptyResults(int expected) {
        List<String> results = new ArrayList<String>(Math.max(0, expected));
        for (int index = 0; index < expected; index++) {
            results.add(null);
        }
        return results;
    }

    /** The 1-based line number a JSON key names, or {@code -1} when it does not name one. */
    private static int lineNumberOf(Object key) {
        if (!(key instanceof String)) {
            return -1;
        }
        try {
            return Integer.parseInt(((String) key).trim());
        } catch (NumberFormatException notALineNumber) {
            return -1;
        }
    }

    /** Posts one chat completion and returns the raw response body. */
    private String post(String system, String text, int maximumTokens, boolean offline) throws Exception {
        return post(system, text, maximumTokens, offline, false);
    }

    /**
     * Posts one chat completion, optionally asking for a JSON object.
     *
     * @param jsonObject add {@code response_format}, so the endpoint is told the reply's shape
     *                   instead of having to infer it from the prompt
     */
    private String post(String system, String text, int maximumTokens, boolean offline,
            boolean jsonObject) throws Exception {
        return http.post(endpoint, requestBody(system, text, maximumTokens, offline, false, jsonObject),
                credentialHeaders());
    }

    /**
     * Posts one batch, asking for a JSON object when this endpoint supports it.
     *
     * <p>An endpoint that answers "I do not know {@code response_format}" is retried once without it
     * and then never asked again: every later batch would otherwise pay for the same rejection, and
     * the numbered fallback already reads a reply that ignores the request.
     *
     * <p>Forgetting the format for good takes an answer that names the field. A non-retryable 400 also
     * covers "this text is too long", an unsupported {@code temperature} and an unknown deployment,
     * and treating any of those as "no JSON mode here" removed the format hint for the rest of the
     * session — the small local models, the ones that need to be told the shape, then answered prose
     * and every batch fell back to one repair request per line.
     */
    private String postBatch(String system, String text, int maximumTokens) throws Exception {
        if (jsonOutput != JsonOutputMode.RESPONSE_FORMAT || jsonOutputRejected) {
            return post(system, text, maximumTokens, false);
        }
        try {
            return post(system, text, maximumTokens, false, true);
        } catch (HttpStatusException rejected) {
            if (!mayBeAboutJsonOutput(rejected)) {
                throw rejected;
            }
            if (mentionsJsonOutput(rejected)) {
                jsonOutputRejected = true;
            }
            return post(system, text, maximumTokens, false);
        }
    }

    /** Whether the endpoint's own message names the output-format field it was sent. */
    private static boolean mentionsJsonOutput(HttpStatusException failure) {
        String message = failure.getMessage() == null
                ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("response_format") || message.contains("response format")
                || message.contains("json_object") || message.contains("json mode");
    }

    /**
     * Posts one batch, halving the budget while the endpoint refuses it, and remembers the value that
     * was accepted.
     *
     * <p>A batch of long lines needs more output tokens than a single line, and every endpoint caps
     * what it will accept. Without the retry, a batch that asked for too much failed and the per-line
     * repair turned one batch into one request per line. Without the memory, every batch of the
     * session paid for the same refusal again: an endpoint capped at 8192 answered 400, refused the
     * halved value as well, and sixteen repair requests followed, so a forty-line screen cost dozens
     * of requests where three would have done.
     *
     * <p>Only a budget the endpoint actually accepted is recorded, and only when a refusal forced it
     * down: one refused request — including one refusing the <em>input</em> for being too long — must
     * not narrow every later batch of that size. The loop itself is {@link BatchBudget}, so the
     * three batching providers cannot drift apart on what may lower a budget, and the memory is held
     * per batch size (see {@link #acceptedBatchTokens}).
     */
    private String postBatchWithinBudget(List<TranslationRequest> group, String system, String text,
            int budget) throws Exception {
        try {
            return BatchBudget.post(acceptedBatchTokens.forEntries(group.size()), budget,
                    MINIMUM_COMPLETION_TOKENS, attempt -> postBatch(system, text, attempt));
        } catch (HttpStatusException rejected) {
            // A refusal that names the request's own length is about how many texts travelled in it:
            // nothing lowered that number, so every later batch was built the same size and refused
            // again, each time for one refused request plus one request per line.
            if (BatchBudget.refusedBecauseTooManyEntries(rejected, text, group)) {
                gatherer.setBatchSize(acceptedBatchSizes.rememberTooLarge(group.size()));
            }
            throw rejected;
        }
    }

    /**
     * Whether a failure is worth one retry without {@code response_format}.
     *
     * <p>{@code 400} is what OpenAI uses for an unknown request argument. The retry is unconditional
     * for that status because it costs one request on a failure that had to be reported anyway, but
     * only an answer that names the field makes the provider stop asking for good — see
     * {@link #mentionsJsonOutput}. A retryable status belongs to the resilient provider above, and any
     * other client status (401, 403, 404, 422) says the request itself is wrong, so a second request
     * would only repeat it.
     */
    private static boolean mayBeAboutJsonOutput(HttpStatusException failure) {
        return !failure.isRetryable() && failure.getStatusCode() == 400;
    }

    /** One chat-completion request body; {@code streaming} selects the event-stream response form. */
    private String requestBody(
            String system,
            String text,
            int maximumTokens,
            boolean offline,
            boolean streaming
    ) {
        return requestBody(system, text, maximumTokens, offline, streaming, false);
    }

    /** One chat-completion request body; {@code jsonObject} adds the JSON output format. */
    private String requestBody(
            String system,
            String text,
            int maximumTokens,
            boolean offline,
            boolean streaming,
            boolean jsonObject
    ) {
        return new StringBuilder(text.length() + 320)
                .append('{')
                .append(promptPrefix(system))
                .append("{\"role\":\"user\",\"content\":").append(JsonStrings.quote(text)).append("}],")
                .append("\"temperature\":0,\"max_tokens\":").append(maximumTokens).append(',')
                .append(jsonObject ? "\"response_format\":{\"type\":\"json_object\"}," : "")
                .append(offline ? "\"repeat_penalty\":1.12," : "")
                .append(streaming ? "\"stream\":true}" : "\"stream\":false}")
                .toString();
    }

    /** The model and system-prompt half of one request body, escaped once per distinct prompt. */
    private static final class PromptBody {
        private final String system;
        private final String prefix;

        private PromptBody(String system, String prefix) {
            this.system = system;
            this.prefix = prefix;
        }
    }

    /**
     * The escaped {@code "model":…,"messages":[{"role":"system"…}} half of a request body.
     *
     * <p>The model never changes and the system prompt changes only with the target language or the
     * prompt settings, so re-escaping both on every request was a few kilobytes of pure repetition per
     * translation — the custom prompt makes that several kilobytes. The cached value is per instance,
     * so two providers can never see each other's prompt.
     *
     * @param system system prompt of this request
     * @return the escaped prefix, ending with the separator before the user message
     */
    private String promptPrefix(String system) {
        PromptBody cached = promptBody;
        if (cached != null && cached.system.equals(system)) {
            return cached.prefix;
        }
        String prefix = "\"model\":" + JsonStrings.quote(model) + ",\"messages\":[{\"role\":\"system\","
                + "\"content\":" + JsonStrings.quote(system) + "},";
        promptBody = new PromptBody(system, prefix);
        return prefix;
    }

    /**
     * Posts with the larger reasoning budget, shrinking and retrying once when the endpoint refuses
     * that budget.
     *
     * <p>Endpoints differ in how much output they allow, so the accepted ceiling is remembered:
     * without that, every later line asks for the same rejected value and is charged for the
     * rejection again. Dropping straight to {@link #MAXIMUM_TOKENS_LIMIT} was wrong, though — that
     * is a plain model's estimate, and a thinking model cannot finish inside it, so the endpoint was
     * left permanently unable to translate anything after a single rejection. Half of the refused
     * value is a guess the model may well fit in, and the retry is what makes it observable.
     *
     * <p>Only a budget the endpoint actually accepted is recorded. Recording half of the refused value
     * before the retry ran meant one refused request — including one refusing the <em>input</em> for
     * being too long — narrowed every later request of the session, down to the floor, and the session
     * could not get the larger budget back.
     */
    private String postWithAcceptedBudget(String system, String text, int budget, boolean offline)
            throws Exception {
        try {
            return post(system, text, budget, offline);
        } catch (HttpStatusException rejected) {
            if (!rejectsCompletionBudget(rejected)) {
                throw rejected;
            }
            int reduced = halvedBudget(budget);
            if (reduced >= budget) {
                // Already at the floor, so there is nothing smaller worth sending.
                throw rejected;
            }
            String accepted = post(system, text, reduced, offline);
            acceptedReasoningTokens = reduced;
            return accepted;
        }
    }

    /**
     * Half of a refused budget, never below {@link #MINIMUM_COMPLETION_TOKENS}.
     *
     * @param budget the refused budget
     * @return half of it
     */
    private static int halvedBudget(int budget) {
        return BatchBudget.halved(budget, MINIMUM_COMPLETION_TOKENS);
    }

    /**
     * Whether a failure looks like "the completion budget you asked for is not allowed".
     *
     * <p>Only client errors whose message names the output-token limit qualify, so an authentication
     * or quota failure is never mistaken for a budget problem and silently retried with a smaller
     * budget. A message about the <em>input</em> being too long mentions "too long" and "exceed" as
     * well, and treating it as an output-budget refusal cut the thinking model's budget in half for a
     * request that had nothing to do with it.
     *
     * <p>Package-private because {@link BatchBudget} makes the same decision for the other two
     * batching providers: a second copy of this whitelist is how the reduction loop would start
     * firing on input-length refusals again.
     */
    static boolean rejectsCompletionBudget(HttpStatusException failure) {
        if (failure.getStatusCode() < 400 || failure.getStatusCode() >= 500) {
            return false;
        }
        String message = failure.getMessage() == null
                ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("context_length") || message.contains("context length")
                || message.contains("prompt") || message.contains("input")) {
            return false;
        }
        return message.contains("max_tokens") || message.contains("max_completion_tokens")
                || message.contains("max_output_tokens") || message.contains("max output");
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
        return describeMissingContent(JsonTree.parseOrNull(response), response);
    }

    /**
     * The same explanation, reusing a body that was already parsed.
     *
     * <p>Every field is read from the parsed document instead of rescanning the text: the scan cost a
     * second full walk of the body per line, and a scan for {@code reasoning_content} also matched the
     * request a relay echoes back, which put the provider's own error text behind a misleading
     * "returned only reasoning content".
     *
     * @param root parsed body, or {@code null} when it was not JSON
     * @param response the same body as text
     * @return the provider's own message when it sent one, otherwise a bounded body excerpt
     */
    static String describeMissingContent(Object root, String response) {
        String providerError = JsonTree.stringAt(root, "error.message");
        if (providerError == null) {
            providerError = JsonTree.stringAt(root, "message");
        }
        if (providerError == null) {
            providerError = JsonTree.stringAt(root, "error");
        }
        if (providerError != null && !providerError.trim().isEmpty()) {
            return "OpenAI-compatible endpoint reported: " + providerError.trim();
        }
        if (reasoningContent(root, "message") != null || reasoningContent(root, "delta") != null) {
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
     * <p>Answering with a {@code choices} array is not enough either, which is what made a thinking
     * model pass: one token is spent thinking, the {@code choices} array exists and its content is
     * empty, and every later translation failed with "returned only reasoning content". The test
     * therefore also has to see a non-empty answer — one token is either a character or nothing — and
     * names the reasoning case when that is what came back.
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
        String response = http.post(endpoint, body, credentialHeaders());
        Object root = JsonTree.parseOrNull(response);
        // A relay that answers 200 with its own error object would otherwise look healthy here,
        // and the user would only find out when every translation failed.
        if (!(root instanceof Map) || !(((Map<?, ?>) root).get("choices") instanceof List)) {
            throw new IllegalStateException(describeMissingContent(response));
        }
        String answer = extractContent(root, response);
        if (answer == null || answer.trim().isEmpty()) {
            // The endpoint answered, but with no translation content: a thinking model that used the
            // single probe token on its chain of thought is exactly this shape.
            throw new IllegalStateException(describeMissingContent(response));
        }
        return response;
    }

    /** Reads the answer out of one reply body; the body is parsed exactly once. */
    static String extractContent(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        return extractContent(JsonTree.parseOrNull(response), response);
    }

    /**
     * Reads the answer out of an already parsed reply.
     *
     * <p>The single-text path needs the content, the reasoning flag and the usage counters of the
     * same document, and every {@code readStringPath} call rebuilt the whole tree. The field scan is
     * kept as the fallback for the relays that answer with a bare {@code content} field.
     *
     * @param root parsed reply, or {@code null} when the body was not JSON
     * @param response the same body as text
     * @return the translation, or {@code null} when the reply carried none
     */
    static String extractContent(Object root, String response) {
        String translated = JsonTree.stringAt(root, "choices[0].message.content");
        if (translated != null && !translated.trim().isEmpty()) {
            return translated;
        }
        if (root != null) {
            // A relay that answers a bare {@code {"content": "..."}} document keeps working, without a
            // second full walk of the body: the field is read from the tree that is already built.
            return JsonTree.stringAt(root, "content");
        }
        return JsonStrings.readStringField(response, "content");
    }

    /**
     * Conservative prompt-token estimate for the offline context.
     *
     * <p>The arithmetic lives in {@link ChatBudget} so this provider and
     * {@link LlamaCppOfflineProvider} cannot disagree about where the context runs out.
     */
    static int estimatePromptTokens(String text) {
        return ChatBudget.estimatePromptTokens(text);
    }

    private static String requireText(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
