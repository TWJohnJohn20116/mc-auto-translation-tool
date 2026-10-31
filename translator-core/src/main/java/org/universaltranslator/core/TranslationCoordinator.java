package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final long WORKER_SHUTDOWN_TIMEOUT_MILLIS = ShutdownBudget.SESSION_CLOSE_MILLIS;
    /**
     * How many dropped sessions may keep their workers before a close that asked for no wait pays
     * one anyway.
     *
     * <p>The platform runtimes bound the <em>closes in flight</em>, and a close that waits nothing is
     * over at once, so that counter never describes a session whose workers are still parked in a
     * provider read. This one does: four dropped sessions of the default worker count are sixteen
     * threads, four of the maximum are sixty-four, which is where a held-down toggle key stops being
     * free and the teardown backlog becomes an executor backlog instead.
     */
    private static final int MAXIMUM_LIVE_RETIRED = 4;
    /**
     * The wait a close that asked for none pays while {@link #MAXIMUM_LIVE_RETIRED} sessions are
     * still lingering with interrupted workers. Short on purpose: those workers were interrupted and
     * are on their way out, so this throttles how fast new sessions may stack up, it does not repeat
     * the five-second budget.
     */
    private static final long RETIRED_SESSION_WAIT_MILLIS = 1_000L;
    /**
     * Dropped sessions whose workers have not left yet, across every coordinator in the process. The
     * worker that leaves last decrements it, so it describes live sessions instead of closes and can
     * never drift upwards.
     */
    private static final AtomicInteger LIVE_RETIRED = new AtomicInteger();
    /**
     * Prefix of every key written to the store. Bumping it invalidates the stored translations, and
     * only that: the in-flight de-duplication key has its own version, because it only has to stay
     * stable inside one session. Sharing one constant meant that a disk format change silently
     * disabled de-duplication too, so the same request was sent twice and billed twice.
     *
     * <p>Bump this whenever the stored <em>values</em> change meaning, not only when the on-disk
     * layout does: the key is the identity of a stored value, so a rule change (a validation
     * accepting a different set of values, a normalization) leaves the old, now unwanted values
     * reachable forever, because nothing makes them invalid. v6 entries were written before
     * {@link TranslationOutputValidator} started keeping a leading quote the source itself carries.
     */
    private static final String CACHE_FORMAT_VERSION = "translation-v7";
    /**
     * Version of the validation and normalization rules a stored value was produced under. Part of
     * the cache key for the same reason as {@link #CACHE_FORMAT_VERSION}, but kept separate so that
     * each rule change is one edit in one place instead of a silent dependency on whoever remembers
     * to bump the format version. It is never written to the store and never part of the in-flight
     * key.
     */
    private static final String VALIDATION_RULES_VERSION = "validate-v2";
    /** Prefix of the in-flight de-duplication key; never written to the store. */
    private static final String REQUEST_KEY_VERSION = "request-v1";
    /**
     * The one stored entry that records which {@link #CACHE_FORMAT_VERSION} and
     * {@link #VALIDATION_RULES_VERSION} wrote the store's contents.
     *
     * <p>Reserved, and not a translation: the store hashes every key it is given, so this entry
     * occupies a slot like any other and the disk store's own load path keeps it (a hashed key is
     * always its fixed digest length), while no cache key built from a source line can ever hash to
     * it. The leading NUL only keeps it out of the readable text space.
     */
    private static final String CACHE_GENERATION_KEY = "\u0000cache-generation";

    private final TranslationProvider provider;
    private final TranslationStore cache;
    private final ThreadPoolExecutor executor;
    /**
     * Runs the completions that cannot be run where they are produced. A future completed on the
     * caller's thread runs its {@code whenComplete} callbacks there, and the caller of
     * {@link #settle} is the render thread; the busy and fatal exits therefore hand the completion
     * to this executor. It is a single daemon thread that only exists once it has work to do.
     */
    private final ExecutorService completions;
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
    /** Guards the once-per-coordinator {@link #migrateStoredGeneration()} run. */
    private final AtomicBoolean cacheGenerationChecked = new AtomicBoolean();
    /**
     * Serializes that run against the other workers of the first burst: the migration clears the
     * store, so a worker that stored a segment while it was reading the file would lose it.
     */
    private final Object cacheGenerationGate = new Object();
    /**
     * Workers currently inside {@link #runWorker}, provider reads included. It is the one state that
     * outlives {@link #close(long)}: a dropped session leaves this above zero until its last
     * interrupted worker comes back, which is what {@link #LIVE_RETIRED} counts.
     */
    private final AtomicInteger runningWorkers = new AtomicInteger();
    /** Guards the once-only entry this session owns in {@link #LIVE_RETIRED}. */
    private final AtomicBoolean retirementCounted = new AtomicBoolean();
    /**
     * Provider of a dropped session, handed to the worker that leaves last instead of being torn down
     * under it. {@code close(long)} writes this before it looks at {@link #runningWorkers} again, so
     * either that worker or the closing thread runs the teardown -- never both, and never while a
     * worker is still inside {@code provider.translate(...)}.
     */
    private volatile AutoCloseable deferredProviderClose;
    /** Guards the once-only run of that deferred teardown. */
    private final AtomicBoolean deferredProviderClaimed = new AtomicBoolean();

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
        this.completions = Executors.newSingleThreadExecutor(new TranslationThreadFactory());
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
                null, false);
    }

    /**
     * The render-path entry point: the caller has already run
     * {@link LanguageHeuristics#shouldTranslateToTarget(String, boolean, boolean)} on this exact text
     * with this session's own {@code chineseTarget} and {@code traditionalTarget}, which are resolved
     * from the same language identifier, so repeating the scan here reaches the same verdict by
     * construction. That scan is a full code point pass, the self-name comparison and the
     * internal-artifact regex, and it used to run a second time on the render thread for every string
     * the hooks saw for the first time.
     *
     * <p>Package-private on purpose: the public overloads must keep doing the check themselves,
     * because a caller that has not run it would otherwise silently skip text that has to be
     * translated.
     *
     * <p>The two booleans are this session's own {@code chineseTarget} and {@code traditionalTarget},
     * taken from the verdict the caller already resolved; they must come from the same
     * {@code targetLanguage} the text verdict was reached for.
     */
    CompletableFuture<TranslationResult> translateKnownTranslatable(
            final String text,
            final String sourceLanguage,
            final String targetLanguage,
            final TextKind kind,
            final Iterable<String> protectedLiterals,
            final boolean preserveHanText,
            final boolean chineseTarget,
            final boolean traditionalTarget
    ) {
        return translate(text, sourceLanguage, targetLanguage, kind, protectedLiterals, preserveHanText,
                null, true, chineseTarget, traditionalTarget);
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
                listener, false);
    }

    /**
     * Shared body of every entry point. {@code listener} is {@code null} for the ordinary path, which
     * must not pay for any of the preview bookkeeping, and {@code heuristicSettled} says whether the
     * caller has already run {@link LanguageHeuristics#shouldTranslateToTarget(String, boolean, boolean)}
     * on this exact text.
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
            final TranslationStreamListener listener,
            final boolean heuristicSettled
    ) {
        // Resolved once per request. shouldTranslate(text, targetLanguage) re-normalizes the language
        // identifier on every call (two String allocations) and this method used it twice — for the
        // raw text and again for the unprotected template — on the caller's thread, which on the
        // render path is the render thread. The identifier cannot change inside one request, so the
        // boolean is a per-request constant.
        final boolean chineseTarget = LanguageHeuristics.isChineseTarget(targetLanguage);
        // Also per request: whether the target asks for Traditional Chinese characters. Without it a
        // Chinese target treats every Han-only string as already translated, so a zh-TW session never
        // converted Simplified input — the only reason that preset exists.
        final boolean traditionalTarget = LanguageHeuristics.isTraditionalTarget(targetLanguage);
        return translate(text, sourceLanguage, targetLanguage, kind, protectedLiterals, preserveHanText,
                listener, heuristicSettled, chineseTarget, traditionalTarget);
    }

    /**
     * The body, with the two language properties already resolved by the caller.
     *
     * <p>Both must come from this exact {@code targetLanguage}: a verdict taken from another language
     * either treats a Han-only line as already translated or skips the other-script conversion. The
     * render path holds them as session constants, and passing them in is what keeps
     * {@link TargetLanguage}'s canonicalization — trim, lower case and the preset loop that builds two
     * strings per iteration — off the render thread for every string the hooks see for the first time.
     */
    private CompletableFuture<TranslationResult> translate(
            final String text,
            final String sourceLanguage,
            final String targetLanguage,
            final TextKind kind,
            final Iterable<String> protectedLiterals,
            final boolean preserveHanText,
            final TranslationStreamListener listener,
            final boolean heuristicSettled,
            final boolean chineseTarget,
            final boolean traditionalTarget
    ) {
        Objects.requireNonNull(text, "text");
        if (targetLanguage == null || targetLanguage.trim().isEmpty()) {
            throw new IllegalArgumentException("targetLanguage is required");
        }
        if (closed.get()) {
            return CompletableFuture.completedFuture(
                    TranslationResult.failure(text, "Translation session is closed"));
        }
        if (!heuristicSettled
                && !LanguageHeuristics.shouldTranslateToTarget(text, chineseTarget, traditionalTarget)) {
            return CompletableFuture.completedFuture(TranslationResult.unchanged(text));
        }

        final String effectiveSource = sourceLanguage == null ? TargetLanguage.AUTO : sourceLanguage.trim();
        final TextKind effectiveKind = kind == null ? TextKind.OTHER : kind;
        // A target that has to change the script makes the Han text the content to translate, not a
        // value to carry over. Protecting it anyway left the template with no letter at all, so both
        // the check below and the worker's own one judged the line untranslatable and a zh-TW target
        // never converted anything -- which is the only reason that preset exists.
        final boolean effectivePreserveHanText = preserveHanText
                && !(chineseTarget && traditionalTarget);

        // Keep all regex construction, cache I/O and provider work off the render thread.
        // The protected-literal snapshot is identified by its contents so that requests with
        // different player-name snapshots cannot accidentally share an in-flight result, while
        // requests that only differ in the list instance still de-duplicate.
        final Submission submission = new Submission(text, effectiveSource, targetLanguage,
                effectiveKind, protectedLiterals, effectivePreserveHanText, chineseTarget,
                traditionalTarget,
                listener, provider.id(), protectedLiteralsSignature(protectedLiterals));
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
            // Not completed here: a CompletableFuture completes its callbacks on the thread that
            // completes it, so the session would run its whole settlement -- session monitor,
            // eviction passes, status report -- on the render thread at the exact moment the queue
            // is full, which is the worst moment to make that thread wait.
            completeOutsideCaller(created, TranslationResult.failure(
                    submission.text, "Translation queue is busy; retrying later"));
        } catch (Throwable fatal) {
            // No explicit TranslationActivity.end() belongs here: completing the future below runs the
            // whenComplete installed on it above, and that is the one end() this begin() is paired
            // with. The second call this used to make was decremented off the count of another request
            // that was still running, because end() only clamps at zero and cannot report the mistake.
            // The future has to be settled as well: ThreadPoolExecutor.execute can fail with an
            // OutOfMemoryError while it creates the worker thread, and an entry left in inFlight
            // would answer every later request for the same text with a future that never completes.
            inFlight.remove(submission.requestKey, created);
            completeOutsideCaller(created,
                    TranslationResult.failure(submission.text, failureMessage(fatal)));
            throw fatal;
        }
        return created;
    }

    /**
     * Completes {@code result} on this coordinator's own thread, so the caller's thread does not run
     * the completion callbacks. Used by the exits of {@link #settle} that can be reached from the
     * render thread.
     */
    private void completeOutsideCaller(
            final CompletableFuture<TranslationResult> future,
            final TranslationResult result
    ) {
        try {
            completions.execute(() -> future.complete(result));
        } catch (RejectedExecutionException shuttingDown) {
            // close() already swept every registered request; settling inline is the last resort.
            future.complete(result);
        }
    }

    /**
     * Drops the stored translations of a previous {@link #CACHE_FORMAT_VERSION} or
     * {@link #VALIDATION_RULES_VERSION} and records the current generation, once per coordinator.
     *
     * <p>Both versions are part of every stored key, so bumping either makes every existing entry
     * unreachable at once: the first session after an upgrade misses on every line -- up to the whole
     * store, ten thousand provider requests, billed ones for a paid provider -- and the old entries
     * keep occupying the entry cap until the new ones push them out, so half the capacity stays
     * unusable meanwhile. Nothing in the store can notice that on its own, because the prefix is
     * hashed into the key, so the generation is kept next to the entries as one reserved entry.
     *
     * <p>Runs on a worker, never on the caller: this reads the disk store and may rewrite it, and the
     * render path must not wait for either.
     *
     * <p>Only a store that reports a generation other than the current one is dropped. A store that
     * reports none proves nothing about what it holds -- a first run, a file this session could not
     * read, and a store whose one reserved entry was pushed out by the entry cap all look like that --
     * and dropping it on that guess is what turned a full cache into nothing once per launch.
     */
    private void migrateStoredGeneration() {
        // Only a store that outlives the process can hold a previous generation; the memory store is
        // built empty together with this coordinator.
        if (!(cache instanceof PersistentTranslationCache)) {
            return;
        }
        if (cacheGenerationChecked.get()) {
            return;
        }
        // The migration has to finish before the other workers of this burst store anything, not just
        // before they run one of their own: clear() empties the map those workers are writing into, so
        // their first paid results were thrown away by the migration that was running next to them.
        synchronized (cacheGenerationGate) {
            if (cacheGenerationChecked.get()) {
                return;
            }
            try {
                String wanted = CACHE_FORMAT_VERSION + '\n' + VALIDATION_RULES_VERSION;
                String stored = cache.get(CACHE_GENERATION_KEY);
                if (wanted.equals(stored)) {
                    return;
                }
                if (stored == null) {
                    // Nothing recorded, so nothing is known to be of an older generation. Writing the
                    // record makes the next launch able to tell, and the entries the store may still
                    // hold are left to age out against the entry cap instead of being deleted here.
                    cache.put(CACHE_GENERATION_KEY, wanted);
                    cache.flush();
                    DebugLog.global().log("cache", "generation-recorded");
                    return;
                }
                cache.clear();
                cache.put(CACHE_GENERATION_KEY, wanted);
                // Written out now: a session that ended before its own flush would leave no generation
                // behind and repeat this clear on the next launch, dropping a whole cache every time.
                cache.flush();
                DebugLog.global().log("cache", "generation-invalidated",
                        "from", stored.replace('\n', ' '),
                        "to", wanted.replace('\n', ' '));
            } catch (RuntimeException failed) {
                // A store that cannot be migrated must not fail the translation that ran into it.
                DebugLog.global().logFailure("cache-generation", failed);
            } finally {
                // Set on the failure path as well: every other worker of this burst would otherwise be
                // sent to the disk to fail the same way.
                cacheGenerationChecked.set(true);
            }
        }
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
        runningWorkers.incrementAndGet();
        try {
            // Once per coordinator, on this worker: the store still holds the entries of the previous
            // key generation, and every one of them is unreachable under the current key.
            migrateStoredGeneration();
            ProtectedText protectedText = ProtectedText.parse(
                    text, submission.protectedLiterals, submission.preserveHanText);
            if (!LanguageHeuristics.shouldTranslateToTarget(
                    protectedText.getUnprotectedTemplateText(), submission.chineseTarget,
                    submission.traditionalTarget)) {
                created.complete(TranslationResult.unchanged(text));
                return;
            }
            PendingCommit pendingCommit = new PendingCommit();
            String restored;
            try {
                restored = TranslationOutputValidator.requireDisplaySafe(
                        text, translateSegments(protectedText, submission, pendingCommit));
            } catch (RuntimeException rejected) {
                // Undo everything this request stored or reused: the next request for the same line
                // has to reach the provider again instead of being served a value that can never be
                // displayed. Rethrown unchanged so the failure the caller reports keeps its type, its
                // message and its cause.
                //
                // A session that was closed under this request is the one verdict that is not about
                // the text: nothing was checked for display, so the segments it already paid for stay
                // where they are -- removing them deleted entries that had passed their own check and
                // put that removal back on disk with the next session's flush.
                pendingCommit.rollback(cache, isSessionClosed(rejected));
                throw rejected;
            }
            // A text whose every segment came back untouched is not a translation. The validator no
            // longer unwinds the request for one repeating segment, so the whole-request verdict is
            // made here: it stays "this text needs no translation" instead of being committed as a
            // success, which cached the source as its own translation and repainted it in the
            // translated colour.
            if (restored.equals(text)) {
                pendingCommit.rollback(cache, false);
                created.complete(TranslationResult.unchanged(text));
                return;
            }
            // A request the close sweep already settled as "session closed" must not be counted as a
            // success afterwards: its caller never receives this result, and the counters are
            // process-wide, so the extra success would never be balanced by anything.
            if (!created.isDone()) {
                pendingCommit.commit();
            }
            created.complete(TranslationResult.success(text, restored));
        } catch (Exception exception) {
            created.complete(settleFailure(text, exception));
        } catch (Throwable fatal) {
            // LinkageError/UnsatisfiedLinkError from the offline native library, OutOfMemoryError and
            // StackOverflowError are not Exception subtypes. Complete the future first so callers
            // never leak an in-flight entry, then let the fatal error propagate and kill the worker
            // thread.
            created.complete(settleFailure(text, fatal));
            throw fatal;
        } finally {
            inFlight.remove(submission.requestKey, created);
            if (runningWorkers.decrementAndGet() == 0) {
                // Nobody is left inside the provider, so this thread is the one that may tear it down,
                // and the one that reports this dropped session as retired.
                releaseDeferredProvider();
                if (retirementCounted.compareAndSet(true, false)) {
                    LIVE_RETIRED.decrementAndGet();
                }
            }
        }
    }

    /**
     * The result one failed request settles with.
     *
     * <p>A provider that hands the source text back is not a failure the caller may retry: the render
     * session classifies every failure as either "the service is down" or "this request can never
     * succeed", and only the second verdict must stop the retries. Rejecting the answer with a plain
     * exception made every unchanged line — a server name, a mod title, "Requires Java 17" in item
     * lore — a retryable one, so the session bought the same unchanged answer again every
     * {@code FAILURE_RETRY_MILLIS} for as long as the line stayed on screen and kept a permanent
     * failure line in the HUD.
     * Settling it as {@link TranslationResult#unchanged(String)} is the verdict the session already
     * remembers once for text that holds nothing translatable.
     */
    private static TranslationResult settleFailure(String text, Throwable failure) {
        if (repeatsSource(failure)) {
            return TranslationResult.unchanged(text);
        }
        return TranslationResult.failure(text, failureMessage(failure));
    }

    /**
     * Whether {@code failure} is the validator's "this answer is the source text" verdict, looking
     * through the stage wrappers a chained request adds.
     */
    private static boolean repeatsSource(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof TranslationOutputValidator.UnchangedOutputException) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    /**
     * Whether {@code failure} is the coordinator's own "this session was closed" verdict rather than
     * something the text did, looking through the stage wrappers a chained request adds.
     */
    private static boolean isSessionClosed(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof SessionClosedException) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
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
        /** Whether Han text is protected; already narrowed by the target script in {@code translate}. */
        private final boolean preserveHanText;
        private final boolean chineseTarget;
        /** Whether the target asks for Traditional Chinese; see {@link LanguageHeuristics}. */
        private final boolean traditionalTarget;
        private final TranslationStreamListener listener;
        private final String requestKey;
        /**
         * The provider identifier, resolved once per request. Asking the provider again for every
         * segment was one interface call per segment on the worker, and every segment still built
         * its key from the same four constants.
         */
        private final String providerId;
        /** {@link #CACHE_FORMAT_VERSION} key prefix shared by every segment of this request. */
        private final String cacheKeyPrefix;

        private Submission(
                String text,
                String sourceLanguage,
                String targetLanguage,
                TextKind kind,
                Iterable<String> protectedLiterals,
                boolean preserveHanText,
                boolean chineseTarget,
                boolean traditionalTarget,
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
            this.traditionalTarget = traditionalTarget;
            this.listener = listener;
            this.providerId = providerId;
            // One builder each instead of eight string concatenations: this runs on the caller's
            // thread, which on the render path is the render thread, and the text is copied once.
            StringBuilder prefix = new StringBuilder(text.length() + 96);
            prefix.append(CACHE_FORMAT_VERSION).append('\n').append(VALIDATION_RULES_VERSION)
                    .append('\n').append(providerId)
                    .append('\n').append(sourceLanguage).append('\n').append(targetLanguage)
                    .append('\n').append(kind).append('\n');
            this.cacheKeyPrefix = prefix.toString();
            StringBuilder key = new StringBuilder(prefix.length() + text.length() + 64);
            key.append(REQUEST_KEY_VERSION).append('\n').append(providerId).append('\n')
                    .append(sourceLanguage).append('\n').append(targetLanguage).append('\n')
                    .append(kind).append('\n').append(preserveHanText).append('\n')
                    .append(literalsSignature).append('\n').append(text);
            this.requestKey = key.toString();
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
            Submission submission,
            PendingCommit pendingCommit
    ) throws Exception {
        StringBuilder output = new StringBuilder(protectedText.getOriginal().length() + 16);
        for (ProtectedText.Segment segment : protectedText.getSegments()) {
            if (segment.isProtectedValue()) {
                output.append(segment.text());
            } else {
                // The text built so far is handed over as the builder itself; the snapshot is only
                // materialized inside translateSegment, and only when the segment really streams.
                // Copying it here meant every unprotected segment copied the whole prefix, even for
                // the cache hits that never publish a preview.
                String translated = translateSegment(segment.text(), submission, output, pendingCommit);
                output.append(translated);
            }
        }
        return output.toString();
    }

    private String translateSegment(
            String segment,
            Submission submission,
            StringBuilder alreadyBuilt,
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
        if (!LanguageHeuristics.shouldTranslateToTarget(core, submission.chineseTarget,
                submission.traditionalTarget)) {
            return segment;
        }
        String providerId = submission.providerId;
        String cacheKey = submission.cacheKeyPrefix + core;
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
                    core, submission.sourceLanguage, submission.targetLanguage, submission.kind);
            TranslationStreamListener segmentListener = null;
            if (submission.listener != null) {
                // Leading whitespace of this segment is not part of what the provider sees, so it is
                // prepended to the preview instead of being lost from it.
                StringBuilder prefix = new StringBuilder(alreadyBuilt.length() + start);
                prefix.append(alreadyBuilt, 0, alreadyBuilt.length()).append(segment, 0, start);
                final String previewPrefix = prefix.toString();
                segmentListener =
                        partialText -> submission.listener.onPartialText(previewPrefix + partialText);
            }
            long startedAt = System.nanoTime();
            try {
                translated = segmentListener == null
                        ? provider.translate(request)
                        : provider.translateStreaming(request, segmentListener);
                if (translated == null || translated.trim().isEmpty()) {
                    throw new IllegalStateException("Provider returned an empty translation");
                }
                try {
                    translated = TranslationOutputValidator.requireValid(core, translated);
                } catch (TranslationOutputValidator.UnchangedOutputException repeated) {
                    // This segment is one the provider hands back untouched -- a brand name, a
                    // "Requires" label -- while the rest of the text is translatable. The verdict
                    // belongs to the segment, so it ends here instead of unwinding the request: the
                    // remaining segments are still sent, and the entries this request already stored
                    // stay. Nothing is counted, like the other "this segment needs no translation"
                    // exit at the top of this method.
                    return segment;
                }
            } catch (Exception failure) {
                // A worker woken by close() reports the provider that was closed under it, and this is
                // the failure side of the verdict the guard below already takes for successes: those
                // counters are process-wide and persisted, so charging the failure here adds one the
                // user never caused. The request itself is still settled as a failure.
                if (!closed.get()) {
                    stats.recordFailure(providerId, TranslationStats.reasonOf(failure),
                            elapsedMillis(startedAt));
                }
                pendingCommit.recordFailure();
                DebugLog.global().logFailure(providerId, failure);
                throw failure;
            }
            // The session can be closed while this worker is parked in the provider read: the sweep in
            // close() reports that request as closed, so this answer is never shown to anybody. Storing
            // it would put a translation no caller ever received into the shared store -- after the
            // close already flushed -- and counting it as a success would add one to process-wide
            // counters that the caller's own view of the request does not contain. The failure branch
            // above is the exit that keeps the request accounted for.
            if (closed.get()) {
                pendingCommit.recordFailure();
                throw new SessionClosedException("Translation session was closed");
            }
            pendingCommit.recordSuccess(providerId, elapsedMillis(startedAt));
            // The key is journalled before the write because a store may accept the entry and only
            // then fail while persisting it; removing a key that was never stored is a no-op.
            pendingCommit.recordStored(cacheKey, translated);
            cache.put(cacheKey, translated);
        } else {
            pendingCommit.recordHit(providerId);
            // The value is adopted, not written, so this request only becomes answerable for it when
            // it is the entry that the assembled text was rejected for; see PendingCommit.rollback.
            pendingCommit.recordAdopted(cacheKey, translated);
        }
        return segment.substring(0, start) + translated + segment.substring(end);
    }

    /** Wall-clock milliseconds since {@code startedAtNanos}, never negative. */
    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    @Override
    public void close() {
        close(WORKER_SHUTDOWN_TIMEOUT_MILLIS);
    }

    /**
     * Releases this session, waiting at most {@code waitMillis} for workers parked in a provider
     * read.
     *
     * <p>Zero is the path a client takes when it drops a session the player has already replaced: a
     * burst of switches (holding the toggle key down, a HUD drag) queues one teardown per press, and
     * every one of them waiting the full budget turns the backlog into minutes of held resource. The
     * wait is what keeps an interrupted worker from looping back into a provider that is about to be
     * torn down, so it stays the default; a caller that has already dropped the session for good skips
     * it, and its provider is then not torn down under the workers either -- the teardown is handed to
     * the worker that leaves last. What such a caller does not get for free is the throttle, so once
     * {@link #MAXIMUM_LIVE_RETIRED} dropped sessions are still lingering this pays
     * {@link #RETIRED_SESSION_WAIT_MILLIS} instead of nothing.
     *
     * <p>Everything that has to be durable happens before that wait: the store is flushed right after
     * the interrupt, so a caller that cuts this call short -- a stop hook joins it with a budget --
     * loses no deferred entry. No thread is started here: the provider teardown runs on this thread
     * whenever this call waited its budget -- even if it did not get its workers back -- and on the
     * last worker only when it skipped the wait.
     */
    void close(long waitMillis) {
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
        // The store may coalesce writes, so the last burst is only durable once this runs -- and it has
        // to run unconditionally. No store implementation has a background flush of its own (the
        // disk-backed one says so in its own javadoc), and the interface contract puts the call on the
        // owner, so skipping it here loses the coalesced window outright the moment this session is
        // dropped.
        //
        // Before the wait, not after it: the flush takes its own snapshot under its own guard, so it
        // never needed the workers to stop, and running it first puts the deferred burst on disk
        // ahead of the only segment of this teardown a caller can cut off -- a client's stop hook
        // joins this call with a budget, and behind a five-second worker wait the flush was the piece
        // that budget threw away.
        cache.flush();
        // Wait for interrupted workers to leave provider.translate(...) before the provider
        // is torn down. Otherwise a worker can loop back into a retrying provider (for
        // example ResilientTranslationProvider retrying an IOException) against an
        // already-closed endpoint and the offline provider kills its child process under a
        // live loopback call. Proceed after the bounded wait so a stuck provider cannot
        // hang Minecraft shutdown, and let the teardown follow the last worker rather than
        // this clock when this close was asked to skip that wait.
        //
        // A close that asked for no wait still pays a short one once too many dropped sessions are
        // lingering: those workers keep their pool, their session state and -- offline -- a child
        // process alive, and nothing else counts them, because a platform counter only sees the
        // closes that are still running and a close that waits nothing is over at once.
        long effectiveWaitMillis = waitMillis;
        if (waitMillis == 0L && runningWorkers.get() > 0
                && LIVE_RETIRED.get() >= MAXIMUM_LIVE_RETIRED) {
            effectiveWaitMillis = RETIRED_SESSION_WAIT_MILLIS;
        }
        boolean workersStopped = false;
        try {
            workersStopped = executor.awaitTermination(
                    effectiveWaitMillis, TimeUnit.MILLISECONDS);
            if (!workersStopped && waitMillis > 0L) {
                // The debug log instead of stderr: this used to be the only core path that wrote to
                // stdout regardless of the debug-log switch, so it printed with no level and no
                // timestamp and could not be turned off. Only a close that asked for a wait can fail
                // to get one: a caller that skips the wait (a replaced session) expects its workers to
                // outlive it, and recording that as an anomaly turned every settings change into
                // shutdown noise.
                DebugLog.global().log("shutdown", "workers", "did-not-stop",
                        "timeoutMillis", String.valueOf(waitMillis));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!workersStopped && waitMillis > 0L) {
            // Same reason as the line above: a close that never asked for a wait cannot have been cut
            // off by one.
            DebugLog.global().log("shutdown", "cache", "flushed-with-live-workers");
        }
        // Graceful, so a completion that is already queued still settles its future; no new request
        // can be registered after close(), and the sweep above has settled everything still in flight.
        completions.shutdown();
        if (!workersStopped && runningWorkers.get() > 0) {
            // The session is dropped with workers still inside it: that is what the platform counters
            // cannot see, and what a burst of switches stacks.
            countRetirement();
        }
        if (provider instanceof AutoCloseable) {
            final AutoCloseable closing = (AutoCloseable) provider;
            if (!workersStopped) {
                // One torn-down provider per dropped session used to leave one more live session
                // behind: a close that had not got its workers back handed the teardown to a thread of
                // its own, so holding the toggle key down stacked hundreds of sessions, each with its
                // own worker pool and -- offline -- its own child process, all of them waiting out the
                // same bound. The provider belongs to this session alone (the platform builds one per
                // session), so tearing it down cannot reach a live one -- but done here it does reach
                // the worker that is still inside provider.translate(...): a retrying provider would
                // meet a closed endpoint on its way out and the offline provider would kill its child
                // process under a live loopback call.
                DebugLog.global().log("shutdown", "provider", "closed-with-live-workers");
            }
            deferredProviderClose = closing;
            // Written before this second look at the worker count: either a worker is still inside the
            // provider and tears it down on its way out, or none is left and this thread does it now.
            // Only the caller that skipped the wait hands it over; one that waited its budget and did
            // not get the workers back keeps the synchronous teardown, and the provider's own shutdown
            // hook covers the child process when the process is going away.
            if (waitMillis > 0L || runningWorkers.get() == 0) {
                releaseDeferredProvider();
            }
        }
    }

    /**
     * Records that this session was dropped with workers still running, once, and releases the record
     * when its last worker leaves.
     *
     * <p>The release is repeated here because a worker can leave between the check in
     * {@link #close(long)} and the count above, and that worker looked for the record before it
     * existed; without this second look the slot it holds would never be given back and every later
     * close would pay the throttled wait.
     */
    private void countRetirement() {
        if (!retirementCounted.compareAndSet(false, true)) {
            return;
        }
        LIVE_RETIRED.incrementAndGet();
        if (runningWorkers.get() == 0 && retirementCounted.compareAndSet(true, false)) {
            LIVE_RETIRED.decrementAndGet();
        }
    }

    /**
     * Runs the provider teardown of a session that was dropped while a worker was still inside it.
     *
     * <p>Called by the closing thread when no worker is left, and by the worker that leaves last
     * otherwise. The claim keeps it to one of them: a second call is a no-op, which is what makes the
     * two entry points safe without a lock.
     */
    private void releaseDeferredProvider() {
        final AutoCloseable deferred = deferredProviderClose;
        if (deferred == null || !deferredProviderClaimed.compareAndSet(false, true)) {
            return;
        }
        deferredProviderClose = null;
        try {
            deferred.close();
        } catch (Exception ignored) {
            // Minecraft is shutting down or applying a replacement configuration.
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
     * What one request did to the cache -- the keys it wrote, the keys it adopted and the counters of
     * its segments -- settled only once the whole text is known to be displayable.
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
     * deliberate: a multi-segment request has written the keys of its earlier segments long before
     * the whole text exists, and a value adopted from the store is already stored by the time it is
     * read. A removal after the fact is therefore the only way to keep a rejected line's own writes
     * out of the cache for good; without it the same key is served to every later round, fails the
     * same check again, and keeps its line broken for the rest of the session while filling a slot in
     * the platform's retry table that can never succeed. What is removed is deliberately narrowed to
     * what this request wrote, plus the single adopted entry of a one-entry request, because the
     * assembled text of a longer request says nothing about its individual entries.
     *
     * <p>One instance per request. The coordinator is called by every worker thread at once, so this
     * state must never live in a field of the coordinator.
     */
    private static final class PendingCommit {
        /**
         * Keys this request wrote itself, with the value it wrote. Only these are dropped when the
         * restored text is rejected: they are this request's own doing.
         */
        private final List<CacheWrite> storedKeys = new ArrayList<CacheWrite>();
        /**
         * Keys this request only read, with the value it read. Kept apart from {@link #storedKeys}
         * because a rejected multi-line request used to delete every key it had merely adopted, so a
         * forty-line tooltip with one bad line lost the thirty-nine good entries and was billed again
         * from scratch on the next round.
         */
        private final List<CacheWrite> adoptedKeys = new ArrayList<CacheWrite>();
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
        /**
         * Whether a provider call of this request is already counted as a failure. A request that
         * fails is settled once, as a failure; counting a rejection on top of it made
         * {@code requests} exceed {@code successes + failures + rejected}.
         */
        private boolean failureRecorded;
        /** Provider of the first looked-up segment, used for the one log line this request writes. */
        private String providerId;

        private void recordStored(String cacheKey, String value) {
            storedKeys.add(new CacheWrite(cacheKey, value));
        }

        private void recordAdopted(String cacheKey, String value) {
            adoptedKeys.add(new CacheWrite(cacheKey, value));
        }

        private void recordFailure() {
            failureRecorded = true;
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
         * Drops what this request wrote, so a rejected value cannot occupy its key.
         *
         * <p>An adopted key is dropped only when this request consisted of that single entry: then
         * the value itself is what the display check rejected, which is the case the removal exists
         * for (a value stored by a version with laxer checks would otherwise be served, rejected and
         * re-served forever). A request with several entries never takes its other, still valid
         * entries down with it.
         *
         * @param keepStored whether the request ended without a display check -- its session was
         *     closed while it was in flight -- so the entries it wrote are still valid and only a
         *     rejected request's bookkeeping is left to settle
         */
        private void rollback(TranslationStore store, boolean keepStored) {
            if (!keepStored) {
                for (CacheWrite write : storedKeys) {
                    removeIfUnchanged(store, write);
                }
                if (storedKeys.size() + adoptedKeys.size() <= 1) {
                    for (CacheWrite write : adoptedKeys) {
                        removeIfUnchanged(store, write);
                    }
                }
            }
            // The request produced nothing usable, so its cache answers stay out of the hit rate and
            // the request itself is counted once, as rejected, instead of leaving orphaned misses.
            // A provider failure already counted this request, so counting a rejection as well would
            // break requests == successes + failures + rejected.
            if (!failureRecorded) {
                TranslationStats.global().recordRejected();
            }
            // Still one line: a rejection is exactly what support needs to see in the log.
            logSummary("cache-rejected");
        }

        /**
         * Removes {@code write} only while the store still holds the value this request saw.
         *
         * <p>The cache key is shared by every line whose segment text happens to be equal, so a
         * concurrent request can have written the same key since. Removing that value would discard a
         * translation another line is displaying right now.
         */
        private static void removeIfUnchanged(TranslationStore store, CacheWrite write) {
            if (write.value.equals(store.get(write.key))) {
                store.remove(write.key);
            }
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

    /** One cache entry this request wrote or read, with the value, so a removal can check it. */
    private static final class CacheWrite {
        private final String key;
        private final String value;

        private CacheWrite(String key, String value) {
            this.key = key;
            this.value = value;
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
