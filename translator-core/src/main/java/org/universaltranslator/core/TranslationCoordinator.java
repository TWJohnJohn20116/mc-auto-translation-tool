package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Collections;

/**
 * Coordinates protection, caching, in-flight de-duplication and background work.
 * It never blocks a Minecraft render thread.
 */
public final class TranslationCoordinator implements AutoCloseable {
    private static final int MAX_QUEUED_TRANSLATIONS = 128;
    private static final long WORKER_SHUTDOWN_TIMEOUT_MILLIS = 5_000L;
    private static final String CACHE_FORMAT_VERSION = "translation-v6";

    private final TranslationProvider provider;
    private final TranslationStore cache;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, CompletableFuture<TranslationResult>> inFlight =
            new ConcurrentHashMap<String, CompletableFuture<TranslationResult>>();
    // Content signature of the protected-literal snapshot that was seen last. The platform
    // republishes that snapshot as a fresh immutable list every few seconds, so an identity
    // based signature both defeated de-duplication (the same line was requested twice across a
    // refresh, and a paid API was billed twice) and could, on a 32-bit identity collision, make
    // two different protection sets share one result. Hashing the contents on every render
    // thread lookup would be too expensive, so the signature is memoized under the assumption
    // the platform publishes an immutable snapshot that keeps its contents until it is replaced
    // by the next refresh; that turns the scan into one pass per refresh.
    private volatile LiteralsSignature literalsSignature;
    private final AtomicBoolean closed = new AtomicBoolean();

    public TranslationCoordinator(TranslationProvider provider, TranslationStore cache, int workerCount) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.cache = Objects.requireNonNull(cache, "cache");
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        this.executor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(MAX_QUEUED_TRANSLATIONS),
                new TranslationThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public CompletableFuture<TranslationResult> translate(
            final String text,
            final String sourceLanguage,
            final String targetLanguage,
            final TextKind kind
    ) {
        return translate(text, sourceLanguage, targetLanguage, kind,
                Collections.<String>emptyList(), true);
    }

    public CompletableFuture<TranslationResult> translate(
            final String text,
            final String sourceLanguage,
            final String targetLanguage,
            final TextKind kind,
            final Iterable<String> protectedLiterals
    ) {
        return translate(text, sourceLanguage, targetLanguage, kind, protectedLiterals, true);
    }

    public CompletableFuture<TranslationResult> translate(
            final String text,
            final String sourceLanguage,
            final String targetLanguage,
            final TextKind kind,
            final Iterable<String> protectedLiterals,
            final boolean preserveHanText
    ) {
        return translate(text, sourceLanguage, targetLanguage, kind, protectedLiterals, preserveHanText,
                null);
    }

    /**
     * Translates one text and reports it while the provider is still generating it.
     *
     * <p>Used by the outgoing-chat path, where the player is looking at the world and waiting for a
     * single line. Everything else about the request is unchanged: the same de-duplication, the same
     * protection of player names and addresses, and the same result — a preview is not a result.
     *
     * <p>Partial texts are never cached. Only the final translation reaches {@code cache.put}, and
     * only after the same validation the non-streaming path applies, so a truncated or empty preview
     * can never be served to a later request.
     *
     * @param listener receives the accumulated translation as it grows, including the final value
     */
    public CompletableFuture<TranslationResult> translateStreaming(
            final String text,
            final String sourceLanguage,
            final String targetLanguage,
            final TextKind kind,
            final Iterable<String> protectedLiterals,
            final boolean preserveHanText,
            final TranslationStreamListener listener
    ) {
        Objects.requireNonNull(listener, "listener");
        return translate(text, sourceLanguage, targetLanguage, kind, protectedLiterals, preserveHanText,
                listener);
    }

    /**
     * Shared body of both entry points. {@code listener} is {@code null} for the ordinary path, which
     * must not pay for any of the preview bookkeeping.
     *
     * <p>Four steps that used to be interleaved in one 130-line method: reject what must not be
     * translated, describe the request, hand it to the workers exactly once, and attach the preview.
     * Separating them is what lets the de-duplication and the activity counter be reviewed — and
     * eventually tested — on their own instead of by reading three different catch blocks together.
     */
    private CompletableFuture<TranslationResult> translate(
            final String text,
            final String sourceLanguage,
            final String targetLanguage,
            final TextKind kind,
            final Iterable<String> protectedLiterals,
            final boolean preserveHanText,
            final TranslationStreamListener listener
    ) {
        Objects.requireNonNull(text, "text");
        if (targetLanguage == null || targetLanguage.trim().isEmpty()) {
            throw new IllegalArgumentException("targetLanguage is required");
        }
        if (closed.get()) {
            return CompletableFuture.completedFuture(
                    TranslationResult.failure(text, "Translation session is closed"));
        }
        // Resolved once per request. shouldTranslate(text, targetLanguage) re-normalizes the language
        // identifier on every call (two String allocations) and this method used it twice — for the
        // raw text and again for the unprotected template — on the caller's thread, which on the
        // render path is the render thread. The identifier cannot change inside one request, so the
        // boolean is a per-request constant.
        final boolean chineseTarget = LanguageHeuristics.isChineseTarget(targetLanguage);
        if (!LanguageHeuristics.shouldTranslateToTarget(text, chineseTarget)) {
            return CompletableFuture.completedFuture(TranslationResult.unchanged(text));
        }

        final String effectiveSource = sourceLanguage == null ? TargetLanguage.AUTO : sourceLanguage.trim();
        final TextKind effectiveKind = kind == null ? TextKind.OTHER : kind;

        // Keep all regex construction, cache I/O and provider work off the render thread.
        // The protected-literal snapshot is identified by its contents so that requests with
        // different player-name snapshots cannot accidentally share an in-flight result, while
        // requests that only differ in the list instance still de-duplicate.
        final Submission submission = new Submission(text, effectiveSource, targetLanguage,
                effectiveKind, protectedLiterals, preserveHanText, chineseTarget, listener,
                provider.id(), protectedLiteralsSignature(protectedLiterals));
        final CompletableFuture<TranslationResult> settled = settle(submission);
        if (listener != null) {
            attachPreview(settled, listener);
        }
        return settled;
    }

    /**
     * The future for this request, submitting a worker when it is the first caller.
     *
     * <p>Only work that is really handed to the provider is counted. A caller that loses the
     * de-duplication race gets the future another thread already counted, and no other exit here
     * performs provider work, so none of them may light the HUD indicator. {@code begin()} sits
     * before {@code executor.execute(...)} so a worker can never settle this future — and therefore
     * run the matching {@code end()} — before the count went up.
     *
     * @return the shared future for this request key
     */
    private CompletableFuture<TranslationResult> settle(final Submission submission) {
        CompletableFuture<TranslationResult> existing = inFlight.get(submission.requestKey);
        if (existing != null) {
            return existing;
        }
        final CompletableFuture<TranslationResult> created =
                new CompletableFuture<TranslationResult>();
        existing = inFlight.putIfAbsent(submission.requestKey, created);
        if (existing != null) {
            // Lost the race: the winner already counted this request and is translating it.
            return existing;
        }
        TranslationActivity.begin();
        // Side-effect only: whenComplete returns a NEW future, which is deliberately discarded so the
        // future handed back to callers keeps its identity and its original exception semantics.
        // whenComplete (not thenRun) so failures, cancellation and close() decrement too.
        created.whenComplete((result, failure) -> TranslationActivity.end());
        try {
            executor.execute(() -> runWorker(submission, created));
        } catch (RejectedExecutionException busy) {
            inFlight.remove(submission.requestKey, created);
            created.complete(TranslationResult.failure(
                    submission.text, "Translation queue is busy; retrying later"));
        } catch (Throwable fatal) {
            // begin() already ran; without this the count would never come back down and the
            // HUD indicator would be stuck showing "translating" for the rest of the session.
            TranslationActivity.end();
            throw fatal;
        }
        return created;
    }

    /**
     * The worker body: protect, translate segment by segment, validate the assembled text, then
     * commit or roll back.
     *
     * <p>The cache writes, the adopted cache hits and the success counters of the segment layer are
     * held back until the restored text is known to be displayable. A value that only fails the
     * display check used to stay cached under its key, was read back by every later round, passed the
     * per-segment check again and failed this one again, so a single such key broke its line for the
     * whole session and occupied a slot in the platform's retry table for good.
     */
    private void runWorker(final Submission submission, final CompletableFuture<TranslationResult> created) {
        final String text = submission.text;
        try {
            ProtectedText protectedText = ProtectedText.parse(
                    text, submission.protectedLiterals, submission.preserveHanText);
            if (!LanguageHeuristics.shouldTranslateToTarget(
                    protectedText.getUnprotectedTemplateText(), submission.chineseTarget)) {
                created.complete(TranslationResult.unchanged(text));
                return;
            }
            PendingCommit pendingCommit = new PendingCommit();
            String restored;
            try {
                restored = TranslationOutputValidator.requireDisplaySafe(
                        text, translateSegments(protectedText, submission.sourceLanguage,
                                submission.targetLanguage, submission.chineseTarget, submission.kind,
                                submission.listener, pendingCommit));
            } catch (RuntimeException rejected) {
                // Undo everything this request stored or reused: the next request for the same line
                // has to reach the provider again instead of being served a value that can never be
                // displayed. Rethrown unchanged so the failure the caller reports keeps its type, its
                // message and its cause.
                pendingCommit.rollback(cache);
                throw rejected;
            }
            pendingCommit.commit();
            created.complete(TranslationResult.success(text, restored));
        } catch (Exception exception) {
            created.complete(TranslationResult.failure(text, failureMessage(exception)));
        } catch (Throwable fatal) {
            // LinkageError/UnsatisfiedLinkError from the offline native library, OutOfMemoryError and
            // StackOverflowError are not Exception subtypes. Complete the future first so callers
            // never leak an in-flight entry, then let the fatal error propagate and kill the worker
            // thread.
            created.complete(TranslationResult.failure(text, failureMessage(fatal)));
            throw fatal;
        } finally {
            inFlight.remove(submission.requestKey, created);
        }
    }

    /**
     * Publishes the settled translation to {@code listener} as one last partial text.
     *
     * <p>The completed translation is published last, so the preview always ends on exactly the text
     * the caller receives. It also covers the request that lost the de-duplication race and was
     * answered by a provider call another thread had already started: that caller still sees the
     * translation arrive, it just arrives complete.
     */
    private static void attachPreview(
            CompletableFuture<TranslationResult> settled,
            final TranslationStreamListener listener
    ) {
        settled.whenComplete((result, failure) -> {
            if (result != null && result.isTranslated()) {
                listener.onPartialText(result.getTranslatedText());
            }
        });
    }

    /**
     * One request as handed to the workers.
     *
     * <p>Immutable, and captured by the worker lambda instead of the caller's eight separate locals.
     * The request key is built here so that everything that changes the answer — and nothing that
     * does not — is in one place.
     */
    private static final class Submission {
        private final String text;
        private final String sourceLanguage;
        private final String targetLanguage;
        private final TextKind kind;
        private final Iterable<String> protectedLiterals;
        private final boolean preserveHanText;
        private final boolean chineseTarget;
        private final TranslationStreamListener listener;
        private final String requestKey;

        private Submission(
                String text,
                String sourceLanguage,
                String targetLanguage,
                TextKind kind,
                Iterable<String> protectedLiterals,
                boolean preserveHanText,
                boolean chineseTarget,
                TranslationStreamListener listener,
                String providerId,
                String literalsSignature
        ) {
            this.text = text;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
            this.kind = kind;
            this.protectedLiterals = protectedLiterals;
            this.preserveHanText = preserveHanText;
            this.chineseTarget = chineseTarget;
            this.listener = listener;
            this.requestKey = CACHE_FORMAT_VERSION + "\n" + providerId + "\n" + sourceLanguage + "\n"
                    + targetLanguage + "\n" + kind + "\n" + preserveHanText + "\n"
                    + literalsSignature + "\n" + text;
        }
    }

    /**
     * Content signature of the protected-literal snapshot, memoized by snapshot identity.
     *
     * <p>Called from {@code translate(...)} on the render thread, so the scan only runs when the
     * platform has published a new snapshot instead of once per lookup.
     */
    private String protectedLiteralsSignature(Iterable<String> protectedLiterals) {
        if (protectedLiterals == null) {
            return "none";
        }
        LiteralsSignature cached = literalsSignature;
        if (cached != null && cached.source == protectedLiterals) {
            return cached.value;
        }
        String value = signatureOf(protectedLiterals);
        literalsSignature = new LiteralsSignature(protectedLiterals, value);
        return value;
    }

    /**
     * Stable signature of the literal contents. Only a {@link List} is walked here: an arbitrary
     * {@link Iterable} is deliberately left alone because reading it on the render thread could
     * consume an iterable that can only be traversed once, and the worker that builds the
     * protected text still has to read it. Such iterables keep the previous identity behaviour.
     */
    private static String signatureOf(Iterable<String> protectedLiterals) {
        if (!(protectedLiterals instanceof List)) {
            return "id:" + System.identityHashCode(protectedLiterals);
        }
        List<?> literals = (List<?>) protectedLiterals;
        int size = literals.size();
        // FNV-1a over the entries. A 64-bit content hash cannot realistically collide the way
        // two 32-bit identity hashes can.
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < size; index++) {
            Object literal = literals.get(index);
            String value = literal instanceof String ? (String) literal : String.valueOf(literal);
            for (int offset = 0; offset < value.length(); offset++) {
                hash ^= value.charAt(offset);
                hash *= 0x100000001b3L;
            }
            // Separate adjacent entries so ["ab", "c"] cannot hash like ["a", "bc"].
            hash ^= 0x0aL;
            hash *= 0x100000001b3L;
        }
        return size + ":" + Long.toHexString(hash);
    }

    /**
     * Translates the unprotected segments of one text and reports the whole text as it is generated.
     *
     * <p>{@code listener} is {@code null} on the ordinary path. What it receives is the message built
     * so far — the segments already translated, the protected values copied through, and the segment
     * currently being generated — because that is the text the HUD line has to show. Nothing here is
     * cached: {@code cache.put} still only ever sees a validated final segment translation, so a
     * partial text can never be served to a later request.
     *
     * @param pendingCommit collects the keys the segments store or adopt from the cache, and the
     *     success counters of the segments that were translated; the caller keeps them until the
     *     restored text has passed the display check, and drops them if it does not
     */
    private String translateSegments(
            ProtectedText protectedText,
            String sourceLanguage,
            String targetLanguage,
            boolean chineseTarget,
            TextKind kind,
            TranslationStreamListener listener,
            PendingCommit pendingCommit
    ) throws Exception {
        StringBuilder output = new StringBuilder(protectedText.getOriginal().length() + 16);
        for (ProtectedText.Segment segment : protectedText.getSegments()) {
            if (segment.isProtectedValue()) {
                output.append(segment.text());
            } else {
                // Only built when a preview is being published: the render path runs through this
                // method for every line the game draws and must not start allocating per segment.
                String alreadyBuilt = listener == null ? null : output.toString();
                output.append(translateSegment(
                        segment.text(), sourceLanguage, targetLanguage, chineseTarget, kind, listener,
                        alreadyBuilt, pendingCommit));
            }
        }
        return output.toString();
    }

    private String translateSegment(
            String segment,
            String sourceLanguage,
            String targetLanguage,
            boolean chineseTarget,
            TextKind kind,
            TranslationStreamListener listener,
            String alreadyBuilt,
            PendingCommit pendingCommit
    ) throws Exception {
        int start = 0;
        int end = segment.length();
        while (start < end && Character.isWhitespace(segment.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(segment.charAt(end - 1))) {
            end--;
        }
        String core = segment.substring(start, end);
        if (!LanguageHeuristics.shouldTranslateToTarget(core, chineseTarget)) {
            return segment;
        }
        String providerId = provider.id();
        String cacheKey = CACHE_FORMAT_VERSION + "\n" + providerId
                + "\n" + sourceLanguage + "\n" + targetLanguage + "\n" + kind + "\n" + core;
        // Cache hits and cache misses are booked on the per-request PendingCommit rather than here.
        // They used to be counted (and logged) the moment a segment was looked up, while the provider
        // successes were held back until the restored text passed the display check, so a request that
        // was rejected after its segments had been assembled left one cache miss behind and no
        // success or failure: the reported hit rate was computed from two different populations.
        TranslationStats stats = TranslationStats.global();
        String translated = cache.get(cacheKey);
        if (translated != null) {
            try {
                translated = TranslationOutputValidator.requireValid(core, translated);
            } catch (IllegalArgumentException invalidCachedValue) {
                translated = null;
            }
        }
        if (translated == null) {
            pendingCommit.recordMiss(providerId);
            TranslationRequest request = new TranslationRequest(
                    core, sourceLanguage, targetLanguage, kind);
            TranslationStreamListener segmentListener = null;
            if (listener != null) {
                // Leading whitespace of this segment is not part of what the provider sees, so it is
                // prepended to the preview instead of being lost from it.
                final String prefix = alreadyBuilt + segment.substring(0, start);
                segmentListener = partialText -> listener.onPartialText(prefix + partialText);
            }
            long startedAt = System.nanoTime();
            try {
                translated = segmentListener == null
                        ? provider.translate(request)
                        : provider.translateStreaming(request, segmentListener);
                if (translated == null || translated.trim().isEmpty()) {
                    throw new IllegalStateException("Provider returned an empty translation");
                }
                translated = TranslationOutputValidator.requireValid(core, translated);
            } catch (Exception failure) {
                stats.recordFailure(providerId, TranslationStats.reasonOf(failure),
                        elapsedMillis(startedAt));
                DebugLog.global().logFailure(providerId, failure);
                throw failure;
            }
            pendingCommit.recordSuccess(providerId, elapsedMillis(startedAt));
            // The key is journalled before the write because a store may accept the entry and only
            // then fail while persisting it; removing a key that was never stored is a no-op.
            pendingCommit.recordKey(cacheKey);
            cache.put(cacheKey, translated);
        } else {
            pendingCommit.recordHit(providerId);
            // The value is adopted, so this request becomes answerable for it. A key whose value
            // passed the per-segment check but whose assembled text is rejected must disappear,
            // otherwise a cache written by a version with laxer checks keeps failing every retry.
            pendingCommit.recordKey(cacheKey);
        }
        return segment.substring(0, start) + translated + segment.substring(end);
    }

    /** Wall-clock milliseconds since {@code startedAtNanos}, never negative. */
    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Complete callers before interrupting workers. Otherwise an interrupted
        // provider can win the race and publish an ordinary failure result even
        // though the whole translation session is being closed.
        //
        // The exception deliberately is a SessionClosedException and not a
        // CancellationException. A CompletableFuture that was handed a CancellationException
        // reports isCancelled() == true, and get(...) then rethrows that exception as itself
        // instead of wrapping it in an ExecutionException -- so a caller could not tell a
        // closed session apart from a cancelled request, and a queued or running request that
        // the session abandoned looked like a cancellation rather than a failure.
        settleOpenRequests();
        executor.shutdownNow();
        // Requests that were registered while the sweep above was running are not covered by
        // its weakly consistent iteration, and the queue drained by shutdownNow never runs the
        // worker that would have settled them. Settling again is safe: the first completion of
        // a future is the one that counts, every later call is a no-op, so no request is
        // settled twice even though both sweeps can see the same entry.
        settleOpenRequests();
        // Wait for interrupted workers to leave provider.translate(...) before the provider
        // is torn down. Otherwise a worker can loop back into a retrying provider (for
        // example ResilientTranslationProvider retrying an IOException) against an
        // already-closed endpoint, the offline provider kills its child process under a
        // live loopback call, and a replacement session races this one on the same
        // cache .tmp file. Proceed after the bounded wait so a stuck provider cannot
        // hang Minecraft shutdown.
        try {
            if (!executor.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                // The debug log instead of stderr: this used to be the only core path that wrote to
                // stdout regardless of the debug-log switch, so it printed with no level and no
                // timestamp and could not be turned off.
                DebugLog.global().log("shutdown", "workers", "did-not-stop",
                        "timeoutMillis", String.valueOf(WORKER_SHUTDOWN_TIMEOUT_MILLIS));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // The store may coalesce writes, so the last burst is only durable once this runs.
        cache.flush();
        if (provider instanceof AutoCloseable) {
            try {
                ((AutoCloseable) provider).close();
            } catch (Exception ignored) {
                // Minecraft is shutting down or applying a replacement configuration.
            }
        }
    }

    /**
     * Fails every request this session still owns with a {@link SessionClosedException} and
     * forgets it, so {@code get(...)} on a queued or running request throws
     * {@link java.util.concurrent.ExecutionException} instead of blocking until its timeout or
     * reporting a cancellation.
     *
     * <p>An entry is removed only when it still maps to the same future, so a request that was
     * registered for the same key after the sweep started keeps its own future. Nothing here
     * blocks: it runs on the caller's thread, on the futures' own lock-free completion path.
     */
    private void settleOpenRequests() {
        for (Map.Entry<String, CompletableFuture<TranslationResult>> entry : inFlight.entrySet()) {
            CompletableFuture<TranslationResult> future = entry.getValue();
            future.completeExceptionally(
                    new SessionClosedException("Translation session was closed"));
            inFlight.remove(entry.getKey(), future);
        }
    }

    /**
     * Terminal signal for a request that the closed session can never answer.
     *
     * <p>An {@link IllegalStateException}, and specifically <em>not</em> a
     * {@link java.util.concurrent.CancellationException}: {@code CompletableFuture} treats a
     * future completed with a cancellation exception as cancelled, so {@code get(...)} rethrows
     * the cancellation itself and the caller cannot separate "shut down under me" from
     * "somebody cancelled this". It is a distinct type rather than a bare
     * {@code IllegalStateException} because the render path has to recognise exactly this
     * outcome: a session that is going away must not be recorded as a retryable provider
     * failure.
     */
    public static final class SessionClosedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public SessionClosedException(String message) {
            super(message);
        }
    }

    /**
     * Failure text for a worker-side throwable. {@link Error} types such as
     * {@link NoClassDefFoundError} frequently carry no message, so fall back to the class name
     * instead of letting {@link TranslationResult#failure} report the generic "Translation failed".
     */
    private static String failureMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable == null ? "Translation failed" : throwable.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * The cache writes, the adopted cache hits and the success counters of one request, released
     * only once the whole text is known to be displayable.
     *
     * <p>The segment layer validates every translation on its own, but the assembled text has three
     * requirements no single segment can be held to, and {@code requireDisplaySafe} rejects it as
     * soon as one of them is broken: the restored text must stay within
     * {@code max(48, source.length() * 3 + 24)} characters, it must not carry a line break when the
     * source has none, and it must not contain one of the instruction fragments an echoed prompt
     * leaves behind (nor the {@code <|system|>}, {@code <|assistant|>} and {@code [quote]} markers
     * that are checked alongside them). Each of those can be produced by the combination of the
     * segments — a sum of per-segment length allowances, a line break or a fragment arriving with a
     * spliced protected value — while every cached value that was consulted is still valid on its
     * own. That two-argument overload deliberately performs no placeholder check, so a
     * placeholder-shaped player name is not a rejection reason and never will be.
     *
     * <p>Writing first and undoing afterwards, rather than writing only after the check, is
     * deliberate: a cache hit cannot be protected by reordering anything because the value is
     * already stored by the time it is read, and a multi-segment request has written the keys of
     * its earlier segments long before the whole text exists. A removal after the fact is therefore
     * the only way to keep a rejected line out of the cache for good; without it the same key is
     * served to every later round, fails the same check again, and keeps its line broken for the
     * rest of the session while filling a slot in the platform's retry table that can never succeed.
     *
     * <p>One instance per request. The coordinator is called by every worker thread at once, so this
     * state must never live in a field of the coordinator.
     */
    private static final class PendingCommit {
        /**
         * Keys this request is answerable for: the ones it stored and the ones it adopted from the
         * cache. Every one of them is dropped if the restored text is rejected.
         */
        private final List<String> touchedKeys = new ArrayList<String>();
        /** Provider calls that succeeded, counted only once the request itself has succeeded. */
        private final List<ProviderSuccess> successes = new ArrayList<ProviderSuccess>();
        /**
         * Cache answers of this request. Held here instead of being counted in the segment as they
         * happen, because a request can still be rejected after all of its segments were assembled:
         * counted eagerly they produced cache numbers with no matching request, and the reported hit
         * rate was a ratio of two differently-scoped populations.
         */
        private int cacheHits;
        private int cacheMisses;
        /** Provider of the first looked-up segment, used for the one log line this request writes. */
        private String providerId;

        private void recordKey(String cacheKey) {
            touchedKeys.add(cacheKey);
        }

        private void recordSuccess(String providerId, long elapsedMillis) {
            successes.add(new ProviderSuccess(providerId, elapsedMillis));
        }

        private void recordHit(String providerId) {
            adopt(providerId);
            cacheHits++;
        }

        private void recordMiss(String providerId) {
            adopt(providerId);
            cacheMisses++;
        }

        private void adopt(String segmentProviderId) {
            if (providerId == null) {
                providerId = segmentProviderId;
            }
        }

        /** Counts the provider calls of a request whose restored text is about to be shown. */
        private void commit() {
            TranslationStats stats = TranslationStats.global();
            for (ProviderSuccess success : successes) {
                stats.recordSuccess(success.providerId, success.elapsedMillis);
            }
            stats.recordCacheHits(cacheHits);
            stats.recordCacheMisses(cacheMisses);
            logSummary("cache");
        }

        /**
         * Drops every key this request stored or adopted, so a rejected value cannot occupy its key.
         *
         * <p>Adopted keys matter here: a value that a previous version accepted and stored can
         * still be served from disk after the checks around it changed, and dropping it on the
         * first rejection is the only thing that lets that line be translated again. Even a value
         * that is valid on its own is worth dropping in that situation, because a line that is
         * rejected as a whole can never be served from it: re-translating costs one provider call
         * and is the only path that can produce a displayable result.
         */
        private void rollback(TranslationStore store) {
            for (String key : touchedKeys) {
                store.remove(key);
            }
            // The request produced nothing usable, so its cache answers stay out of the hit rate and
            // the request itself is counted once, as rejected, instead of leaving orphaned misses.
            TranslationStats.global().recordRejected();
            // Still one line: a rejection is exactly what support needs to see in the log.
            logSummary("cache-rejected");
        }

        /**
         * Writes this request's cache outcome as one log line.
         *
         * <p>The coordinator used to log every segment separately, so one forty-line item tooltip
         * produced forty lines and forty file-system appends -- all of them serialized on the debug
         * log's lock while the four workers were waiting on it. The information is the same at the
         * request level, and the count of appends drops with the segment count.
         *
         * @param event log event name, so a rejected request is recognisable in the file
         */
        private void logSummary(String event) {
            if (providerId == null) {
                return;
            }
            DebugLog.global().log(event,
                    "provider", providerId,
                    "segments", Integer.toString(cacheHits + cacheMisses),
                    "hits", Integer.toString(cacheHits),
                    "misses", Integer.toString(cacheMisses),
                    "provider-calls", Integer.toString(successes.size()));
        }
    }

    /** One provider call that already succeeded, kept until its request as a whole is accepted. */
    private static final class ProviderSuccess {
        private final String providerId;
        private final long elapsedMillis;

        private ProviderSuccess(String providerId, long elapsedMillis) {
            this.providerId = providerId;
            this.elapsedMillis = elapsedMillis;
        }
    }

    /**
     * Memoized content signature of one protected-literal snapshot. Held in a single volatile
     * field so a reader can never observe a signature that belongs to a different snapshot.
     */
    private static final class LiteralsSignature {
        private final Iterable<String> source;
        private final String value;

        private LiteralsSignature(Iterable<String> source, String value) {
            this.source = source;
            this.value = value;
        }
    }

    private static final class TranslationThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "universal-translator-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
