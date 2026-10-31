package org.universaltranslator.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/**
 * Non-blocking lookup facade for render hooks. The first frame returns the original;
 * completed translations are substituted on later frames.
 */
public final class RenderTranslationSession implements AutoCloseable {
    private static final long FAILURE_RETRY_MILLIS = 30_000L;
    private static final int MAX_PENDING_TRANSLATIONS = 128;
    private static final int MAX_RENDERED_TRANSLATIONS = 4_096;
    // The re-entry guard is bounded separately from the translation map: it has to outlive
    // the translation it guards, otherwise evicted outputs are translated all over again.
    private static final int MAX_RENDERED_OUTPUTS = 8_192;
    // Heuristics-rejected text never produces a translation, so it needs its own bound. Sized like
    // the rendered outputs because the same lobby-tab-list load reaches both.
    private static final int MAX_REJECTED_TEXTS = 8_192;
    // How many render lookups accumulate before the debug log gets one summary line.
    private static final int PROBE_REPORT_INTERVAL = 1_024;
    private static final int MAX_FAILED_TRANSLATIONS = 1_024;
    // A request whose future is never completed (for example an Error escaping the coordinator's
    // worker) would otherwise hold a pending slot forever, so the guard is kept. It has to be
    // longer than the slowest request the provider wiring can produce, including retries, or a
    // request that is still legitimately retrying is reclaimed and submitted a second time.
    //
    // Worst case for one submitted request, from OnlineProviderConfig.create(...) and
    // ResilientTranslationProvider:
    //   api-max-attempts            = 5          (clamped to 1..5, so 5 is the ceiling)
    //   api-connect-timeout-ms      = 60_000     (clamped to 250..60_000)
    //   api-read-timeout-ms         = 300_000    (clamped to 1_000..300_000)
    //   api-min-request-interval-ms = 60_000     (clamped to 0..60_000, waited once per attempt)
    //   retry backoff               = 200 + 400 + 800 + 1_600 = 3_000
    //                                 (200 << (attempt - 1), capped at 2_000, slept between attempts)
    // 5 * (60_000 + 300_000 + 60_000) + 3_000 = 2_103_000 ms, so 40 minutes keeps the guard
    // strictly above the slowest request the configuration can produce. Time spent waiting in the
    // coordinator's bounded work queue is not covered by this bound.
    private static final long PENDING_EXPIRY_MILLIS = 2_400_000L;
    /**
     * How often a full {@code pending} is swept. The sweep only runs when the map is at its cap, and
     * at the cap every render lookup used to sweep all 128 entries -- thirty thousand map iterations
     * per frame in a lobby, on the render thread, precisely while everything was already slow.
     */
    private static final long PENDING_RECLAIM_INTERVAL_MILLIS = 1_000L;
    private static final int MAX_BACKGROUND_SUBMISSIONS_PER_SECOND = 4;
    private static final int MAX_PRIORITY_SUBMISSIONS_PER_SECOND = 12;
    /**
     * Slot count of the formatting-strip memo. Sized like the styling memo: one slot per line that is
     * on screen at the same time, so the drawn lines do not evict each other between frames.
     */
    private static final int STRIPPED_MEMO_SLOTS = 64;
    /**
     * Slots of the tooltip-line memos. Two or three lists are on screen at once (an item tooltip next
     * to a HUD panel, a WAILA-style overlay over the inventory), and with a single slot an alternating
     * A/B/A/B sequence missed on every frame. Four slots cover the alternating case while keeping only
     * a few dozen line references alive.
     */
    private static final int LINE_MEMO_SLOTS = 4;

    private final TranslationCoordinator coordinator;
    private final String providerCategory;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final TranslationDisplayMode displayMode;
    private final boolean preserveHanText;
    /** Whether {@code targetLanguage} is Chinese; resolved once because the render path asks per string. */
    private final boolean chineseTarget;
    /**
     * Whether {@code targetLanguage} asks for Traditional Chinese characters. Resolved once for the
     * same reason as {@link #chineseTarget}, and needed because a Chinese target may only skip Han
     * text when it does not have to convert it to the other script.
     */
    private final boolean traditionalTarget;
    private final ConcurrentHashMap<RenderKey, String> translated = new ConcurrentHashMap<RenderKey, String>();
    private final ConcurrentHashMap<String, Boolean> translatedOutputs =
            new ConcurrentHashMap<String, Boolean>();
    // Maps an in-flight lookup to the time it was submitted, so an abandoned future can be
    // reclaimed instead of permanently consuming one of the MAX_PENDING_TRANSLATIONS slots.
    private final ConcurrentHashMap<RenderKey, Long> pending = new ConcurrentHashMap<RenderKey, Long>();
    private final ConcurrentHashMap<RenderKey, Long> retryAfter = new ConcurrentHashMap<RenderKey, Long>();
    // Text the heuristics rejected. Without this every frame paid for the whole scan again: a
    // rejection never reaches `translated`, so nothing stopped the same string from being re-tested
    // sixty times a second. shouldTranslate is a pure function of (text, targetLanguage) and both are
    // fixed for the life of a session, so the verdict can be cached for as long as the session lives.
    private final ConcurrentHashMap<RenderKey, Boolean> rejected = new ConcurrentHashMap<RenderKey, Boolean>();
    // Insertion order for the bounded eviction of translated, translatedOutputs and retryAfter.
    // Keys that were already evicted stay in the queue harmlessly; the remove is simply a no-op.
    private final ConcurrentLinkedQueue<RenderKey> renderedTranslationOrder =
            new ConcurrentLinkedQueue<RenderKey>();
    private final ConcurrentLinkedQueue<String> renderedOutputOrder =
            new ConcurrentLinkedQueue<String>();
    private final ConcurrentLinkedQueue<RenderKey> retryOrder =
            new ConcurrentLinkedQueue<RenderKey>();
    private final ConcurrentLinkedQueue<RenderKey> rejectedOrder =
            new ConcurrentLinkedQueue<RenderKey>();
    private final SubmissionWindow backgroundSubmissions =
            new SubmissionWindow(MAX_BACKGROUND_SUBMISSIONS_PER_SECOND);
    private final SubmissionWindow prioritySubmissions =
            new SubmissionWindow(MAX_PRIORITY_SUBMISSIONS_PER_SECOND);
    // Render-path probe counters. Written only by the render thread, read only by recordProbe.
    private long probeLookups;
    private long probeSubstituted;
    private long probeNanos;
    private volatile boolean closed;
    /** When {@link #reclaimExpiredPending} may run again; see the interval constant. */
    private volatile long nextPendingReclaimAtMillis;
    // Memo for the tooltip join. Screen.getTooltipFromItem calls the line path once per frame with
    // the same list instance, so without this every frame built a fresh ~1 KB String and re-hashed it
    // inside the lookup key. The slot is only where a candidate is looked for: it is accepted after
    // comparing the contents it was built from, and the entry keeps its own copy of those lines, so a
    // caller that edits its list in place can never be served the previous frame's join.
    private final AtomicReferenceArray<JoinedLines> joinedLines =
            new AtomicReferenceArray<JoinedLines>(LINE_MEMO_SLOTS);
    /**
     * Finished output of {@link #lookupLines} in translated-only mode, one slot per line list. A
     * single slot meant the alternating tooltips of one screen rebuilt the join, the lookup and the
     * split of both lists on every frame.
     */
    private final AtomicReferenceArray<LinesMemo> translatedLinesMemo =
            new AtomicReferenceArray<LinesMemo>(LINE_MEMO_SLOTS);
    /**
     * Finished output of {@link #lookupLines} in bilingual mode, one slot per line list.
     *
     * <p>Slotted for the same reason as {@link #translatedLinesMemo}: two tooltips on screen at once
     * take turns, and one slot made that an A/B/A/B sequence that missed on every frame.
     */
    private final AtomicReferenceArray<LinesMemo> bilingualLinesMemos =
            new AtomicReferenceArray<LinesMemo>(LINE_MEMO_SLOTS);
    /**
     * Line lists whose joined translation came back with a different number of lines.
     *
     * <p>That verdict is final for as long as both the lines and the translation are unchanged, but it
     * used to leave no trace at all: every frame the tooltip was drawn re-split the translation and
     * threw it away again. Four slots, like the memos it belongs to.
     */
    private final AtomicReferenceArray<SplitFailure> splitFailures =
            new AtomicReferenceArray<SplitFailure>(LINE_MEMO_SLOTS);
    /**
     * Identity memo for the formatting-stripped form used by the re-entry guard, one slot per line
     * instead of one slot per session. Only consulted for text that carries a formatting code, which
     * is exactly the text whose strip would otherwise allocate a copy of the whole line on the render
     * thread every frame. A single slot is not a memo for this path: the drawn lines take turns, so a
     * second formatted line on screen made every lookup a miss and copied the whole line again.
     */
    private final AtomicReferenceArray<StrippedFormatting> strippedInputs =
            new AtomicReferenceArray<StrippedFormatting>(STRIPPED_MEMO_SLOTS);
    /**
     * The same memo for our own translated output. It is a second array because the two questions have
     * different populations: every formatted line the hooks see asks "is this our own output", while
     * only the lines we produced ask for their stripped form. Sharing one array let the hundreds of
     * new formatted labels a lobby produces per second evict the entries of the stable translated
     * lines, so those were stripped -- a StringBuilder plus a copy of the whole line -- again on every
     * frame, which is the cost that made the memo worth adding.
     */
    private final AtomicReferenceArray<StrippedFormatting> strippedOutputs =
            new AtomicReferenceArray<StrippedFormatting>(STRIPPED_MEMO_SLOTS);
    private volatile String lastFailureStatus = "";
    /**
     * The status already written to the log, so a provider that keeps failing with the same message
     * does not append a line per request. It belongs to {@link #lastFailureStatus}: both are cleared
     * together by {@link #clearFailureStatus()}.
     */
    private volatile String printedFailureStatus = "";
    private volatile TranslationBlocklist blockedKeywords = TranslationBlocklist.empty();
    private volatile Supplier<? extends Iterable<String>> protectedLiterals =
            new Supplier<Iterable<String>>() {
                @Override
                public Iterable<String> get() {
                    return Collections.emptyList();
                }
            };

    public RenderTranslationSession(
            TranslationProvider provider,
            String sourceLanguage,
            String targetLanguage,
            int maximumCacheEntries,
            int workerCount
    ) {
        this(provider, sourceLanguage, targetLanguage, new TranslationCache(maximumCacheEntries), workerCount,
                TranslationDisplayMode.TRANSLATED_ONLY, true);
    }

    public RenderTranslationSession(
            TranslationProvider provider,
            String sourceLanguage,
            String targetLanguage,
            TranslationStore store,
            int workerCount
    ) {
        this(provider, sourceLanguage, targetLanguage, store, workerCount,
                TranslationDisplayMode.TRANSLATED_ONLY, true);
    }

    public RenderTranslationSession(
            TranslationProvider provider,
            String sourceLanguage,
            String targetLanguage,
            TranslationStore store,
            int workerCount,
            TranslationDisplayMode displayMode
    ) {
        this(provider, sourceLanguage, targetLanguage, store, workerCount, displayMode, true);
    }

    public RenderTranslationSession(
            TranslationProvider provider,
            String sourceLanguage,
            String targetLanguage,
            TranslationStore store,
            int workerCount,
            TranslationDisplayMode displayMode,
            boolean preserveHanText
    ) {
        this.coordinator = new TranslationCoordinator(provider, store, workerCount);
        this.providerCategory = safeProviderCategoryForLog(provider.id());
        this.sourceLanguage = sourceLanguage == null ? TargetLanguage.AUTO : sourceLanguage;
        if (targetLanguage == null || targetLanguage.trim().isEmpty()) {
            this.coordinator.close();
            throw new IllegalArgumentException("targetLanguage is required");
        }
        this.targetLanguage = targetLanguage.trim();
        this.displayMode = displayMode == null
                ? TranslationDisplayMode.TRANSLATED_ONLY : displayMode;
        this.preserveHanText = preserveHanText;
        this.chineseTarget = LanguageHeuristics.isChineseTarget(this.targetLanguage);
        this.traditionalTarget = LanguageHeuristics.isTraditionalTarget(this.targetLanguage);
    }

    /**
     * Looks up {@code original} for the render hooks.
     *
     * <p>Only times the call when debug logging is on; with the log off this is a plain delegation
     * that the JIT inlines, so per-frame callers pay nothing for the probe.
     */
    public String lookup(String original, TextKind kind) {
        DebugLog probeLog = DebugLog.global();
        if (!probeLog.isEnabled()) {
            return lookupInternal(original, kind);
        }
        long startedAt = System.nanoTime();
        String result = lookupInternal(original, kind);
        recordProbe(probeLog, System.nanoTime() - startedAt, original == result);
        return result;
    }

    private String lookupInternal(String original, TextKind kind) {
        if (closed || original == null || original.isEmpty()) {
            return original;
        }
        // A GUI text can pass through both a high-level draw hook and TextRenderer.
        // Never submit our own completed output for translation a second time. This stays ahead of
        // the render cache: our own output must never be looked up as if it were an input.
        if (isCompletedOutput(original)) {
            return original;
        }
        TextKind effectiveKind = kind == null ? TextKind.OTHER : kind;
        RenderKey key = new RenderKey(original, effectiveKind);
        // Probe the render cache before the heuristics. An entry can only exist for text that
        // already passed LanguageHeuristics.shouldTranslate with this session's fixed target
        // language, and that check is a pure function of (text, targetLanguage). Text the
        // heuristics reject therefore has no entry, so the returned value is unchanged while a
        // cache hit stops paying for the full-text scan on every frame.
        String ready = translated.get(key);
        if (ready != null) {
            // Re-arm the re-entry guard while serving. The guard has its own bound and is evicted
            // independently of this map, and an output whose guard entry is gone is handed back to
            // the translation path as if it were a source line: it is requested from the provider a
            // second time and, in bilingual mode, comes back with a second prefix. A missing entry is
            // the only case that has to write, so the hit path is one lookup instead of one write.
            if (!translatedOutputs.containsKey(ready)) {
                rememberRenderedOutput(ready);
            }
            rememberStrippedVariant(ready);
            return ready;
        }
        if (rejected.containsKey(key)) {
            return original;
        }
        // A keyword-filtered line and a heuristics-rejected line are the same thing to this cache:
        // the caller gets the original back. Both are remembered, so the keyword scan (a lower-case
        // copy plus a contains per keyword) runs once per string instead of once per frame.
        // setBlockedKeywords() clears the whole set, so a changed filter re-evaluates every string.
        if (blockedKeywords.matches(original)) {
            rememberRejected(key);
            return original;
        }
        // Avoid building player-name snapshots for text that is already in the
        // target language or contains no words worth translating. Both target properties are session
        // constants, so this verdict is final for this string: the coordinator is told below that it
        // has already been reached instead of repeating the same scan on the same text.
        if (!LanguageHeuristics.shouldTranslateToTarget(original, chineseTarget, traditionalTarget)) {
            rememberRejected(key);
            return original;
        }

        Long retryAt = retryAfter.get(key);
        long now = System.currentTimeMillis();
        if (retryAt != null && retryAt.longValue() > now) {
            return original;
        }
        // A busy multiplayer lobby can expose thousands of rapidly changing
        // strings in a few frames. Drop excess render-time work and try again on
        // a later frame instead of growing an unbounded queue and freezing MC.
        if (pending.size() >= MAX_PENDING_TRANSLATIONS) {
            // Reclaim work whose future was never completed before giving up. Without this an
            // Error escaping the coordinator's worker would fill the map to the cap and
            // permanently short-circuit every lookup, disabling all render translation.
            reclaimExpiredPending(now);
            if (pending.size() >= MAX_PENDING_TRANSLATIONS) {
                return original;
            }
        }
        if (pending.containsKey(key)) {
            return original;
        }
        // A global font hook can see hundreds of unique labels per second in a lobby.
        // Keep the local model from running at 100% continuously. Interactive and HUD
        // surfaces use a separate allowance so tooltips and chat are not starved by
        // world-space labels. The token is acquired only when this frame actually hands
        // work to the coordinator, so frames that reuse an in-flight lookup do not spend it.
        final SubmissionWindow window = submissionWindow(effectiveKind);
        if (!window.tryAcquire()) {
            return original;
        }
        final Long submittedAt = Long.valueOf(now);
        if (pending.putIfAbsent(key, submittedAt) != null) {
            // Another frame already submitted this exact key; the budget was not spent.
            window.refund();
            return original;
        }
        Iterable<String> literals;
        try {
            literals = protectedLiterals.get();
        } catch (RuntimeException ignored) {
            literals = Collections.emptyList();
        }
        CompletableFuture<TranslationResult> request;
        try {
            // The heuristic verdict for this exact text and these exact target properties was reached
            // a few lines above, so the coordinator must not repeat that scan on the render thread.
            // The two language properties are session constants here and are handed over rather than
            // re-derived: deriving them canonicalizes the identifier, which is a preset loop with two
            // short-lived strings per iteration, once per newly seen string on the render thread.
            request = coordinator.translateKnownTranslatable(
                    original, sourceLanguage, targetLanguage, effectiveKind, literals, preserveHanText,
                    chineseTarget, traditionalTarget);
        } catch (Throwable failure) {
            // A provider can fail with an Error (for example an UnsatisfiedLinkError from the
            // offline native library) before a future exists at all. Release both the pending
            // entry and the token so one failure cannot disable render translation.
            pending.remove(key, submittedAt);
            window.refund();
            throw failure;
        }
        // The pending entry must be released on every outcome, including a future that is only
        // completed on shutdown or one that completes exceptionally. whenComplete covers the
        // success and error paths; a future that is never completed at all is reclaimed by
        // reclaimExpiredPending, so the entry can never leak permanently.
        request.whenComplete((result, error) -> {
            boolean published = completeLookup(key, submittedAt, original, result, error);
            if (!published && error == null && result != null
                    && !result.isFailure() && !result.isTranslated()) {
                // The text held nothing translatable (for example a scoreboard line that is
                // entirely a protected value such as a server address). No provider work was
                // done, so give the token back instead of starving genuinely translatable text.
                // Failures are deliberately excluded: their retry backoff already throttles
                // them, and refunding would remove that protection during an outage.
                window.refund();
            }
        });
        return original;
    }

    /**
     * Releases the pending entry for {@code key} and records the outcome.
     *
     * @return true when a translated result was published, false when the submission produced no
     *         translation (unchanged, failure, blocked or closed).
     */
    /**
     * Settles one lookup, and reports a changed failure status only after leaving the monitor.
     *
     * <p>The monitor is shared with {@link #clearRenderedTranslations()}, {@link #close()} and
     * {@link #setBlockedKeywords}, so nothing inside it should take long. The bilingual output of a
     * translation is built before the monitor is entered (three strings are concatenated for it),
     * and the status line is written to the log after it is left.
     */
    private boolean completeLookup(
            RenderKey key,
            Long submittedAt,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        FailureReport report = new FailureReport();
        String published = publishableOutput(original, result);
        boolean changed = completeLookupLocked(
                key, submittedAt, original, result, error, report, published);
        report.print(providerCategory);
        return changed;
    }

    /** The text a translated result publishes, or {@code null} for every other outcome. */
    private String publishableOutput(String original, TranslationResult result) {
        if (result == null || !result.isTranslated()) {
            return null;
        }
        return formatOutput(original, result.getTranslatedText());
    }

    private synchronized boolean completeLookupLocked(
            RenderKey key,
            Long submittedAt,
            String original,
            TranslationResult result,
            Throwable error,
            FailureReport report,
            String published
    ) {
        // Always run, even when the session is closing: a pending entry that is never
        // removed permanently consumes one of the MAX_PENDING_TRANSLATIONS slots. The value
        // check keeps a late completion from clearing a newer submission of the same key.
        pending.remove(key, submittedAt);
        if (closed) {
            return false;
        }
        if (error != null && isSessionClosed(error)) {
            // The coordinator settles a request it can no longer answer with a
            // SessionClosedException when its session is torn down (a settings reload replaces the
            // whole session, so this session's own close() is not always what ran first). That is
            // not a provider failure: recording it below would put the key into the retry table
            // for FAILURE_RETRY_MILLIS and re-submit the same line into a session that no longer
            // exists, and it would publish a misleading "翻译失败" line into latest.log. The pending
            // entry is already released above, so the slot is not lost.
            retryAfter.remove(key);
            return false;
        }
        // The filter can be replaced while an older request is still completing.
        // Never publish a result that is blocked by the current configuration.
        if (blockedKeywords.matches(original)) {
            retryAfter.remove(key);
            return false;
        }
        if (isUnchangedOutput(error)) {
            // The provider handed the source text back, so it has nothing to translate here. That is
            // a final verdict, not a failure worth retrying: queuing it made the line cost one paid
            // request every FAILURE_RETRY_MILLIS for the rest of the session. Remembering it is the
            // exit the "nothing translatable" path already uses.
            retryAfter.remove(key);
            rememberRejected(key);
            return false;
        }
        if (error != null || result == null || result.isFailure()) {
            // Bound on the map, not on the insertion-order queue: ConcurrentLinkedQueue.size() walks
            // every node, and this check runs inside the session monitor on every failure callback --
            // exactly when a failing provider produces a burst of them. ConcurrentHashMap.size() is
            // an amortised counter sum, and the map is what the eviction actually empties, so it is
            // also the honest number.
            if (retryAfter.size() >= MAX_FAILED_TRANSLATIONS) {
                // Evict the oldest entries instead of clearing every key: a full clear makes
                // all failed strings retryable at the same moment and produces a retry burst.
                evictOldestFailedTranslations(MAX_FAILED_TRANSLATIONS / 8);
            }
            if (retryAfter.put(key, System.currentTimeMillis() + FAILURE_RETRY_MILLIS) == null) {
                retryOrder.add(key);
            }
            String status = safeFailureStatus(error, result);
            report.status = failureStatusIfChanged(status);
            lastFailureStatus = status;
            return false;
        }
        // The status the platform shows; the log marker deliberately survives a single success, so a
        // line that keeps failing with the same message is not appended to latest.log every time.
        lastFailureStatus = "";
        retryAfter.remove(key);
        if (result.isTranslated()) {
            if (translated.size() >= MAX_RENDERED_TRANSLATIONS) {
                // Evict the oldest entries instead of clearing the map: a full clear dropped every
                // cached line at once, so the whole screen fell back to the original text and had
                // to be translated again over the next few dozen frames. Only the translation map
                // is evicted here. translatedOutputs is the re-entry guard that stops the mod from
                // translating its own rendered output; losing it would re-submit every evicted line
                // (and compound the bilingual prefix). Its own bound is larger and evicts the same
                // fraction of its cap, so it still outlives the keys this map retains.
                evictOldestRenderedTranslations(MAX_RENDERED_TRANSLATIONS / 8);
            }
            if (translatedOutputs.size() >= MAX_RENDERED_OUTPUTS) {
                evictOldestRenderedOutputs(MAX_RENDERED_OUTPUTS / 8);
            }
            if (translated.put(key, published) == null) {
                renderedTranslationOrder.add(key);
            }
            rememberRenderedOutput(published);
            rememberStrippedVariant(published);
            return true;
        }
        // The text held nothing translatable -- every segment was a protected value such as a server
        // address or a player name. Without a remembered verdict the same line is re-submitted, and
        // therefore re-parsed and re-validated, on every single frame for the whole session.
        rememberRejected(key);
        return false;
    }

    /**
     * Drops pending entries whose future was never completed, so the map cannot stay wedged at
     * its cap. Only runs when the map is full, and at most once per
     * {@link #PENDING_RECLAIM_INTERVAL_MILLIS}: while an endpoint is stuck the map stays at its cap,
     * and sweeping 128 entries on every lookup of every frame is the one thing this path cannot
     * afford.
     */
    private void reclaimExpiredPending(long now) {
        if (now < nextPendingReclaimAtMillis) {
            return;
        }
        nextPendingReclaimAtMillis = now + PENDING_RECLAIM_INTERVAL_MILLIS;
        long cutoff = now - PENDING_EXPIRY_MILLIS;
        for (Map.Entry<RenderKey, Long> entry : pending.entrySet()) {
            Long submittedAt = entry.getValue();
            if (submittedAt != null && submittedAt.longValue() <= cutoff) {
                pending.remove(entry.getKey(), submittedAt);
            }
        }
    }

    private void rememberRenderedOutput(String output) {
        // A hit is the common case on the render path; testing it first keeps the write path (and
        // the possible resize behind it) out of every frame.
        if (translatedOutputs.containsKey(output)) {
            return;
        }
        if (translatedOutputs.putIfAbsent(output, Boolean.TRUE) == null) {
            renderedOutputOrder.add(output);
        }
    }

    /** Removes roughly {@code count} of the oldest rendered translation entries. */
    private void evictOldestRenderedTranslations(int count) {
        evictOldest(renderedTranslationOrder, translated, count);
    }

    /** Removes roughly {@code count} of the oldest re-entry guard entries. */
    private void evictOldestRenderedOutputs(int count) {
        evictOldest(renderedOutputOrder, translatedOutputs, count);
    }

    /** Removes roughly {@code count} of the oldest retry entries. */
    private void evictOldestFailedTranslations(int count) {
        evictOldest(retryOrder, retryAfter, count);
    }

    private static <K, V> void evictOldest(
            ConcurrentLinkedQueue<K> order,
            ConcurrentHashMap<K, V> values,
            int count
    ) {
        for (int index = 0; index < count; index++) {
            K oldest = order.poll();
            if (oldest == null) {
                return;
            }
            values.remove(oldest);
        }
    }

    /**
     * Translates related lines as one request so item names and lore retain context.
     * The original list is returned until the background translation is ready.
     *
     * <p>Every return value is read-only, so a caller cannot edit an answer that several frames share
     * and cannot observe that the first frame handed it a mutable list while a later frame handed it an
     * unmodifiable one. A platform wrapper that answers a render call without reaching this method --
     * translation switched off, a settings screen, the chat input -- has to wrap its own list the same
     * way, or the contract holds only while translation is running.
     */
    public List<String> lookupLines(List<String> originals, TextKind kind) {
        if (originals == null) {
            return null;
        }
        if (originals.isEmpty()) {
            return Collections.unmodifiableList(originals);
        }
        TextKind effectiveKind = kind == null ? TextKind.OTHER : kind;
        // Bilingual formatting inserts the original and translated text together.
        // Preserve the established one-output-line-per-input-line behavior in that mode.
        if (displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED) {
            int slot = lineMemoSlot(originals, effectiveKind);
            LinesMemo memo = bilingualLinesMemos.get(slot);
            if (memo != null && memo.matchesAllTranslated(originals, effectiveKind)) {
                return memo.output();
            }
            // Built only once a line really changed: a fully untranslated list -- the state every
            // frame starts in while the first request is out -- used to allocate one list per frame
            // and throw it away again.
            List<String> replacement = null;
            int translatedCount = 0;
            for (int index = 0; index < originals.size(); index++) {
                String original = originals.get(index);
                // A line that is already translated keeps its answer, so it is taken from the previous
                // frame's memo instead of being looked up again. A line that still shows its own text
                // is looked up, because that lookup is what re-submits the retry that may translate it.
                String line = memo != null && memo.reusable(index, originals, effectiveKind)
                        ? memo.outputAt(index) : lookup(original, effectiveKind);
                boolean lineChanged = original == null ? line != null : !original.equals(line);
                if (lineChanged) {
                    translatedCount++;
                    if (replacement == null) {
                        replacement = new ArrayList<String>(originals.size());
                        for (int prior = 0; prior < index; prior++) {
                            replacement.add(originals.get(prior));
                        }
                    }
                }
                if (replacement != null) {
                    replacement.add(line);
                }
            }
            if (replacement == null) {
                return Collections.unmodifiableList(originals);
            }
            // A partly translated list is remembered as well. The lines it answered are final, so
            // honouring them is what keeps a forty-line tooltip from looking all forty up again on
            // every frame while the rest is still arriving; the lines it could not answer are never
            // served from it.
            if (memo != null && memo.sameOutcome(originals, effectiveKind, translatedCount)) {
                return memo.output();
            }
            LinesMemo rebuilt = new LinesMemo(originals, effectiveKind, replacement);
            bilingualLinesMemos.set(slot, rebuilt);
            return rebuilt.output();
        }
        // The whole result is remembered, not just the join: a hit means the joined line is already
        // translated, so splitting it again only rebuilt n substrings for a list that cannot have
        // changed since the last frame.
        int slot = lineMemoSlot(originals, effectiveKind);
        LinesMemo memo = translatedLinesMemo.get(slot);
        if (memo != null && memo.matches(originals, effectiveKind)) {
            return memo.output();
        }
        JoinedLines joined = joinedLines(originals, slot);
        String originalText = joined.text;
        if (originalText == null) {
            return Collections.unmodifiableList(originals);
        }
        String translatedText = lookup(originalText, effectiveKind);
        if (originalText.equals(translatedText)) {
            return Collections.unmodifiableList(originals);
        }
        SplitFailure failed = splitFailures.get(slot);
        if (failed != null && failed.matches(originals, effectiveKind, translatedText)) {
            // This very translation of these very lines already came back with the wrong number of
            // lines, and neither of them changed since, so splitting it again cannot answer anything.
            return Collections.unmodifiableList(originals);
        }
        List<String> translatedLines = splitLines(translatedText);
        if (translatedLines.size() != originals.size()) {
            rememberSplitFailure(slot, originals, effectiveKind, translatedText, translatedLines.size());
            return Collections.unmodifiableList(originals);
        }
        LinesMemo rebuilt = new LinesMemo(originals, effectiveKind, translatedLines);
        translatedLinesMemo.set(slot, rebuilt);
        return rebuilt.output();
    }

    /**
     * Records that the joined translation of {@code originals} cannot be split back onto them.
     *
     * <p>Written only when the verdict is new, so the log line appears once per mismatching list
     * instead of once per frame. The mismatch itself is the answer to "why is this tooltip never
     * translated": the translation is stored under the joined text and stays there, so without the
     * record nothing anywhere said the line count was the reason.
     */
    private void rememberSplitFailure(
            int slot,
            List<String> originals,
            TextKind kind,
            String translatedText,
            int translatedLines
    ) {
        SplitFailure previous = splitFailures.get(slot);
        if (previous != null && previous.matches(originals, kind, translatedText)) {
            return;
        }
        splitFailures.set(slot, new SplitFailure(originals, kind, translatedText));
        DebugLog.global().log("render-lines", "line-count-mismatch",
                "lines", Integer.toString(originals.size()),
                "translated", Integer.toString(translatedLines));
    }

    /**
     * One remembered {@link #lookupLines} result: the input lines, the kind they were looked up with,
     * and the list to hand back.
     *
     * <p>Immutable and replaced as a whole, so a reader can never observe a source from one frame next
     * to an output from another. Both lists are copies: the memo is validated by comparing contents, so
     * keeping the caller's instance meant a caller that edited its own list in place matched its stale
     * entry forever and kept being served the previous frame's output, with nothing in the log.
     *
     * <p>Validity is the input contents, not a global publish counter: the counter is bumped by every
     * translation of every line on screen, so keyed on it this memo missed on nearly every frame while
     * translations were arriving and rebuilt both list copies each time.
     */
    private static final class LinesMemo {
        private final List<String> source;
        private final TextKind kind;
        private final List<String> output;
        /** How many lines of {@link #output} differ from the line they were produced from. */
        private final int translatedCount;

        private LinesMemo(List<String> source, TextKind kind, List<String> output) {
            this.source = Collections.unmodifiableList(new ArrayList<String>(source));
            this.kind = kind;
            this.output = Collections.unmodifiableList(new ArrayList<String>(output));
            int translated = 0;
            for (int index = 0; index < this.source.size() && index < this.output.size(); index++) {
                String sourceLine = this.source.get(index);
                String outputLine = this.output.get(index);
                if (sourceLine == null ? outputLine != null : !sourceLine.equals(outputLine)) {
                    translated++;
                }
            }
            this.translatedCount = translated;
        }

        private boolean matches(List<String> current, TextKind currentKind) {
            return kind == currentKind && sameLines(source, current);
        }

        /** Whether every line is translated, so this memo alone answers the call. */
        private boolean matchesAllTranslated(List<String> current, TextKind currentKind) {
            if (kind != currentKind || source.size() != current.size()
                    || translatedCount != source.size()) {
                return false;
            }
            for (int index = 0; index < source.size(); index++) {
                String cachedSource = source.get(index);
                String line = current.get(index);
                if (cachedSource == null ? line != null : !cachedSource.equals(line)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether line {@code index} of {@code current} may be answered from this memo.
         *
         * <p>Only an already translated line qualifies. A line that is still showing its own text has
         * to be looked up again, because that lookup is what re-submits the retry that may translate
         * it; serving it from the memo would keep it untranslated for the whole session.
         */
        private boolean reusable(int index, List<String> current, TextKind currentKind) {
            if (kind != currentKind || source.size() != current.size()
                    || index >= current.size()) {
                return false;
            }
            String cachedSource = source.get(index);
            String cachedOutput = output.get(index);
            if (cachedSource == null ? cachedOutput == null : cachedSource.equals(cachedOutput)) {
                return false;
            }
            String line = current.get(index);
            return cachedSource == null ? line == null : cachedSource.equals(line);
        }

        private String outputAt(int index) {
            return output.get(index);
        }

        /** Whether this memo already answers {@code current} with {@code translated} translated lines. */
        private boolean sameOutcome(List<String> current, TextKind currentKind, int translated) {
            return kind == currentKind && translatedCount == translated && sameLines(source, current);
        }

        private List<String> output() {
            return output;
        }
    }

    /**
     * The joined form of one line list, published as a whole.
     *
     * <p>{@link #text} is {@code null} when one line cannot survive the round trip. That verdict is
     * published too: recomputing it on every frame meant one multi-line lore line -- a book page, a
     * modded item description -- was scanned again sixty times a second.
     */
    private static final class JoinedLines {
        private final List<String> source;
        private final String text;

        private JoinedLines(List<String> source, String text) {
            // A copy, for the same reason LinesMemo copies its own source: the memo is accepted by
            // comparing contents, and the list the tooltip path passes is the one it fills in place.
            // Holding the caller's instance made an edited list equal to its own stale entry, so the
            // previous frame's join was returned for the new lines -- and the translation of the old
            // item stayed on the new one for as long as the list kept changing.
            this.source = Collections.unmodifiableList(new ArrayList<String>(source));
            this.text = text;
        }
    }

    /**
     * One remembered line-count mismatch: the lines, the kind they were looked up with, and the
     * translation whose split did not land back on them.
     *
     * <p>Bound to the translation as well as to the lines, so a retry that answers with a different
     * number of lines is tried again instead of being answered from here. The source is a copy for the
     * same reason {@link LinesMemo} keeps one: the tooltip path fills its list in place.
     */
    private static final class SplitFailure {
        private final List<String> source;
        private final TextKind kind;
        private final String translatedText;

        private SplitFailure(List<String> source, TextKind kind, String translatedText) {
            this.source = Collections.unmodifiableList(new ArrayList<String>(source));
            this.kind = kind;
            this.translatedText = translatedText;
        }

        private boolean matches(List<String> current, TextKind currentKind, String currentTranslated) {
            return kind == currentKind && translatedText.equals(currentTranslated)
                    && sameLines(source, current);
        }
    }

    /**
     * Joins the lines with {@code '\n'}, reusing an earlier result when the same lines are passed
     * again, and reports {@code null} text when one line cannot survive the round trip.
     *
     * <p>{@code slot} only says where to look; the entry found there is accepted after
     * {@link #sameLines} confirmed its contents, so a list that shares the slot with another list
     * costs one rebuild instead of a wrong answer.
     */
    private JoinedLines joinedLines(List<String> originals, int slot) {
        JoinedLines previous = joinedLines.get(slot);
        if (previous != null && sameLines(previous.source, originals)) {
            return previous;
        }
        StringBuilder joined = new StringBuilder(originals.size() * 32);
        for (int index = 0; index < originals.size(); index++) {
            String line = originals.get(index);
            if (line == null || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                JoinedLines unjoinable = new JoinedLines(originals, null);
                joinedLines.set(slot, unjoinable);
                return unjoinable;
            }
            if (index > 0) {
                joined.append('\n');
            }
            joined.append(line);
        }
        JoinedLines result = new JoinedLines(originals, joined.toString());
        joinedLines.set(slot, result);
        return result;
    }

    /**
     * Whether {@code cached} holds the same lines as {@code current}.
     *
     * <p>Content, not identity: the tooltip path rebuilds its list from the components on every
     * frame, so an identity check would never hit. Equal lines always join to the same text, so a
     * content match can never serve a stale join -- provided {@code cached} is a snapshot and not the
     * caller's own list, which is why both memos store a copy.
     */
    private static boolean sameLines(List<String> cached, List<String> current) {
        return cached != null && cached.equals(current);
    }

    /**
     * Slot of {@code lines} in the two translated-only line memos ({@code joinedLines} and
     * {@code translatedLinesMemo}).
     *
     * <p>A content hash, because identity would put every rebuilt tooltip list in a new slot; the
     * same FNV-1a mix the protected-literal signature uses, and no allocation. It is a hint only: the
     * memo verifies the whole list before it answers, so a collision costs one rebuild.
     */
    private static int lineMemoSlot(List<String> lines, TextKind kind) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line != null) {
                for (int offset = 0; offset < line.length(); offset++) {
                    hash ^= line.charAt(offset);
                    hash *= 0x01000193;
                }
            }
            // Separates adjacent lines so ["ab", "c"] cannot hash like ["a", "bc"].
            hash ^= 0x1f;
            hash *= 0x01000193;
        }
        hash ^= kind.ordinal();
        hash *= 0x01000193;
        return (hash ^ (hash >>> 16)) & (LINE_MEMO_SLOTS - 1);
    }

    /**
     * Splits on {@code '\n'} keeping empty fields, without compiling a regex per call.
     *
     * <p>{@code split("\\n", -1)} is a two-character pattern, so the JDK's fast paths do not apply and
     * every hover recompiled it; this is the same thing by hand.
     */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<String>();
        int from = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines.add(text.substring(from, index));
                from = index + 1;
            }
        }
        lines.add(text.substring(from));
        return lines;
    }

    /**
     * Submits an interactive translation and exposes its completion to a platform adapter.
     * This is used for outgoing chat: the caller can cancel the original send, then send the
     * completed result on Minecraft's main thread without ever blocking that thread.
     */
    public CompletableFuture<TranslationResult> translateInteractive(
            String original,
            TextKind kind,
            String requestedTargetLanguage,
            boolean preserveHanText
    ) {
        return translateInteractive(original, kind, requestedTargetLanguage, preserveHanText, false);
    }

    /**
     * Same request, optionally publishing the translation while the provider is still generating it.
     *
     * <p>Streaming is opt-in per call because it only pays off for the one text the player is waiting
     * on: the outgoing chat line. Everything else — the lines the render hooks ask for — is drawn from
     * the cache and has no visible latency to hide.
     *
     * @param streaming when true, the partial translation is published to
     *                  {@link OutgoingTranslationPreview} for the HUD until the request settles
     */
    public CompletableFuture<TranslationResult> translateInteractive(
            String original,
            TextKind kind,
            String requestedTargetLanguage,
            boolean preserveHanText,
            boolean streaming
    ) {
        if (original == null) {
            throw new IllegalArgumentException("original cannot be null");
        }
        String target = requestedTargetLanguage == null ? "" : requestedTargetLanguage.trim();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("targetLanguage is required");
        }
        if (blockedKeywords.matches(original)) {
            return CompletableFuture.completedFuture(TranslationResult.unchanged(original));
        }
        Iterable<String> literals;
        try {
            literals = protectedLiterals.get();
        } catch (RuntimeException ignored) {
            literals = Collections.emptyList();
        }
        TextKind effectiveKind = kind == null ? TextKind.CHAT : kind;
        if (!streaming) {
            return coordinator.translate(
                    original, sourceLanguage, target, effectiveKind, literals, preserveHanText);
        }
        OutgoingTranslationPreview.Handle preview = OutgoingTranslationPreview.begin();
        CompletableFuture<TranslationResult> pending = coordinator.translateStreaming(
                original, sourceLanguage, target, effectiveKind, literals, preserveHanText, preview);
        // The preview must not outlive its request. whenComplete (not thenRun) so a failure, a
        // cancellation and the session closing all release the HUD line too; the returned future is
        // the one callers already hold, so this callback is attached to it and not chained onto it.
        pending.whenComplete((result, failure) -> preview.finish());
        return pending;
    }

    private boolean isCompletedOutput(String text) {
        if (translatedOutputs.containsKey(text)) {
            return true;
        }
        if (text.indexOf('\u00a7') < 0) {
            // No formatting code, so the stripped form is this very string and the lookup above was
            // the whole answer. Text without a code is the common case on the modern render paths,
            // which is why the strip is not attempted unconditionally.
            return false;
        }
        String unformatted = strippedVariant(strippedInputs, text);
        return unformatted != text && translatedOutputs.containsKey(unformatted);
    }

    /**
     * Remembers the formatting-stripped form of {@code output} as a completed output too.
     *
     * <p>Re-arming the guard is what keeps our own output from being translated again, so it runs on
     * every hit; the strip that produces it does not have to run again, because the memo already holds
     * this very line. Skipping the memo lookup was not an option: with one slot the second formatted
     * line on screen copied the whole line on every frame.
     */
    private void rememberStrippedVariant(String output) {
        if (output.indexOf('\u00a7') < 0) {
            return;
        }
        rememberRenderedOutput(strippedVariant(strippedOutputs, output));
    }

    /**
     * The formatting-stripped form of {@code text}, remembered by identity in {@code memo}.
     *
     * <p>{@code TranslationTextStyling.stripLegacyFormatting} builds a StringBuilder and copies the
     * line character by character whenever a formatting code is present, and the re-entry guard asked
     * for that copy on every frame for a text that is not yet known to be one of our outputs. Identity
     * is enough to reuse the answer because a {@link String} never changes its contents, and each memo
     * holds one slot per line on screen, so two drawn lines no longer evict each other between frames.
     */
    private static String strippedVariant(
            AtomicReferenceArray<StrippedFormatting> memo, String text) {
        int slot = strippedSlot(text);
        StrippedFormatting cached = memo.get(slot);
        if (cached != null && cached.source == text) {
            return cached.result;
        }
        StrippedFormatting computed = new StrippedFormatting(
                text, TranslationTextStyling.stripLegacyFormatting(text));
        memo.set(slot, computed);
        return computed.result;
    }

    /**
     * Slot of {@code text} in a {@link StrippedFormatting} memo.
     *
     * <p>The identity hash of a string never changes, so the same line always lands in the same slot.
     * Two different lines that share one slot only take turns without ever seeing each other's result,
     * because a hit is an identity comparison, not a hash comparison.
     */
    private static int strippedSlot(String text) {
        int hash = System.identityHashCode(text);
        return (hash ^ (hash >>> 16)) & (STRIPPED_MEMO_SLOTS - 1);
    }

    /**
     * The stripped form of one line, published as a whole.
     *
     * <p>Two independent volatile fields could be written by two workers in an interleaved order and
     * leave one line's source next to another line's result, after which every later lookup of the
     * first line returned the second line's stripped form: our own translation was then re-submitted
     * (and billed) as if it were source text, or a real source line was skipped. A single immutable
     * holder is published in one write, so a reader sees both fields of the same line or neither.
     */
    private static final class StrippedFormatting {
        private final String source;
        private final String result;

        private StrippedFormatting(String source, String result) {
            this.source = source;
            this.result = result;
        }
    }

    private String formatOutput(String original, String translatedText) {
        if (displayMode != TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                || original.equals(translatedText)) {
            return translatedText;
        }
        return original + " \u00a78| \u00a7f" + translatedText;
    }

    public void setProtectedLiteralsSupplier(Supplier<? extends Iterable<String>> supplier) {
        if (supplier == null) {
            throw new IllegalArgumentException("supplier cannot be null");
        }
        this.protectedLiterals = supplier;
    }

    /** Replaces the configured whole-text keyword filter. */
    public void setBlockedKeywords(String configuredKeywords) {
        TranslationBlocklist.Parsed parsed = TranslationBlocklist.parseWithReport(configuredKeywords);
        if (parsed.lossy()) {
            // The screen saves the whole field as if it took effect, so at least keep the loss
            // visible: keywords past the cap never match, which leaves text the player asked to keep
            // off the network on its way to the provider.
            DebugLog.global().log("blocklist", "dropped", String.valueOf(parsed.dropped()),
                    "truncated", String.valueOf(parsed.truncated()));
        }
        this.blockedKeywords = parsed.blocklist();
        clearRenderedTranslations();
    }

    /** Latest render-time failure, cleared after the next successful request. */
    public String lastFailureStatus() {
        return lastFailureStatus;
    }

    /**
     * Whether {@code error} is the coordinator's terminal "this session was closed" signal rather
     * than a provider failure, looking through the stage wrappers a chained future adds.
     */
    private static boolean isSessionClosed(Throwable error) {
        Throwable current = error;
        // Bounded walk and a self-reference guard: a cause chain that points back at itself must not
        // spin here, and neither must a provider that wraps the same failure indefinitely.
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof TranslationCoordinator.SessionClosedException) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    /**
     * Whether {@code error} is the validator's "the provider answered with the source text" verdict,
     * looking through the stage wrappers a chained future adds.
     */
    private static boolean isUnchangedOutput(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof TranslationOutputValidator.UnchangedOutputException) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    private static String safeFailureStatus(Throwable error, TranslationResult result) {
        String message = result == null ? null : result.getErrorMessage();
        if ((message == null || message.trim().isEmpty()) && error != null) {
            message = error.getMessage();
        }
        if (message == null || message.trim().isEmpty()) {
            message = "未知错误";
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (singleLine.length() > 120) {
            int end = 117;
            if (Character.isHighSurrogate(singleLine.charAt(end - 1))) {
                // Cutting between the halves of one code point leaves a lone surrogate, and the UTF-8
                // encoder that writes the log turns it into a replacement character.
                end--;
            }
            singleLine = singleLine.substring(0, end) + "...";
        }
        return "翻译失败：" + singleLine;
    }

    /** Carries the failure status from inside the session monitor to the log write that follows it. */
    private static final class FailureReport {
        private String status;

        private void print(String providerCategory) {
            if (status == null) {
                return;
            }
            // The debug log instead of stderr: stderr has no level, no timestamp and cannot be turned
            // off, and this runs on whatever thread settled the request -- the render thread when the
            // queue is busy. The same status still reaches the player through lastFailureStatus().
            DebugLog.global().log("render-failure",
                    "provider", providerCategory, "status", status);
        }
    }

    /**
     * The status to report for {@code status}, or {@code null} when this exact status was already
     * reported.
     *
     * <p>Must be called under the session monitor: it updates {@code printedFailureStatus}.
     */
    private String failureStatusIfChanged(String status) {
        if (status.equals(printedFailureStatus)) {
            return null;
        }
        printedFailureStatus = status;
        return status;
    }

    /** Forgets the last failure status together with the log marker that belongs to it. */
    private void clearFailureStatus() {
        lastFailureStatus = "";
        printedFailureStatus = "";
    }

    static String safeProviderCategoryForLog(String providerId) {
        if (providerId == null || providerId.trim().isEmpty()) {
            return "unknown";
        }
        String singleLine = providerId.replace('\n', ' ').replace('\r', ' ').trim();
        int separator = singleLine.indexOf(':');
        String category = separator < 0 ? singleLine : singleLine.substring(0, separator);
        StringBuilder safe = new StringBuilder(Math.min(category.length(), 40));
        for (int index = 0; index < category.length() && safe.length() < 40; index++) {
            char value = category.charAt(index);
            if ((value >= 'a' && value <= 'z')
                    || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9')
                    || value == '-' || value == '_' || value == '.') {
                safe.append(value);
            } else {
                safe.append('_');
            }
        }
        return safe.length() == 0 ? "unknown" : safe.toString();
    }

    /**
     * Remembers that the heuristics rejected {@code key}, so later frames reach the same verdict with
     * one hash lookup instead of another full-text scan. Bounded exactly like the translation map.
     */
    private void rememberRejected(RenderKey key) {
        if (rejected.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        rejectedOrder.add(key);
        if (rejected.size() <= MAX_REJECTED_TEXTS) {
            return;
        }
        // A fixed number of polls, like evictOldest: the queue keeps keys that were already evicted,
        // and "poll until the map is small enough" could drain the whole queue in one call because
        // removing a key that is already gone does not shrink the map.
        for (int index = 0; index < MAX_REJECTED_TEXTS / 8; index++) {
            RenderKey oldest = rejectedOrder.poll();
            if (oldest == null) {
                return;
            }
            rejected.remove(oldest);
        }
    }

    /**
     * Writes one probe line to the debug log every {@link #PROBE_REPORT_INTERVAL} render lookups.
     *
     * <p>Called only while debug logging is on, and only from the render thread, so the counters need
     * no synchronization. They exist because the render path runs hundreds of times per frame and the
     * only trustworthy way to judge a change there is a measurement from a real client.
     */
    private void recordProbe(DebugLog probeLog, long elapsedNanos, boolean unchanged) {
        probeLookups++;
        probeNanos += elapsedNanos;
        if (!unchanged) {
            probeSubstituted++;
        }
        if (probeLookups < PROBE_REPORT_INTERVAL) {
            return;
        }
        long averageNanos = probeNanos / probeLookups;
        probeLog.log("render-path",
                "lookups", Long.toString(probeLookups),
                "substituted", Long.toString(probeSubstituted),
                "avg_us", Long.toString(averageNanos / 1_000L),
                "cached", Integer.toString(translated.size()),
                "rejected", Integer.toString(rejected.size()),
                "pending", Integer.toString(pending.size()));
        probeLookups = 0L;
        probeSubstituted = 0L;
        probeNanos = 0L;
    }

    public synchronized void clearRenderedTranslations() {
        translated.clear();
        translatedOutputs.clear();
        renderedTranslationOrder.clear();
        renderedOutputOrder.clear();
        pending.clear();
        retryAfter.clear();
        retryOrder.clear();
        rejected.clear();
        rejectedOrder.clear();
        // The line-path memos answer from the tables that were just emptied.
        for (int slot = 0; slot < LINE_MEMO_SLOTS; slot++) {
            translatedLinesMemo.set(slot, null);
            joinedLines.set(slot, null);
            bilingualLinesMemos.set(slot, null);
            splitFailures.set(slot, null);
        }
        for (int slot = 0; slot < STRIPPED_MEMO_SLOTS; slot++) {
            strippedInputs.set(slot, null);
            strippedOutputs.set(slot, null);
        }
        clearFailureStatus();
        nextPendingReclaimAtMillis = 0L;
        backgroundSubmissions.reset();
        prioritySubmissions.reset();
    }

    private SubmissionWindow submissionWindow(TextKind kind) {
        switch (kind) {
            case CHAT:
            case SYSTEM_MESSAGE:
            case SCOREBOARD_TITLE:
            case SCOREBOARD_LINE:
            case PLAYER_LIST_HEADER:
            case PLAYER_LIST_FOOTER:
            case ACTION_BAR:
            case TITLE:
            case SUBTITLE:
            case BOSS_BAR:
            case CONTAINER_TITLE:
            case ITEM_NAME:
            case ITEM_LORE:
            case TOOLTIP:
            case SIGN:
            case BOOK:
            case DISCONNECT_REASON:
                return prioritySubmissions;
            default:
                return backgroundSubmissions;
        }
    }

    /**
     * Hands out a fixed number of submission slots per second, one at a time.
     *
     * <p>This used to be a fixed one-second window that reset the whole quota at the boundary, so the
     * last instant of one window plus the first of the next let twice the configured rate through
     * within a few milliseconds -- exactly the burst the quota exists to prevent: it is what protects
     * the local model and the coordinator's 128-slot queue.
     */
    private static final class SubmissionWindow {
        private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1L);
        private final int maximum;
        private final long refillIntervalNanos;
        private long lastRefillNanos;
        private int available;

        private SubmissionWindow(int maximum) {
            this.maximum = maximum;
            this.refillIntervalNanos = Math.max(1L, NANOS_PER_SECOND / maximum);
            this.lastRefillNanos = System.nanoTime();
            this.available = maximum;
        }

        private synchronized boolean tryAcquire() {
            refill(System.nanoTime());
            if (available <= 0) {
                return false;
            }
            available--;
            return true;
        }

        private void refill(long now) {
            if (available >= maximum) {
                // Full: keep the reference point current, so an idle second cannot bank more than one
                // second's worth of slots.
                lastRefillNanos = now;
                return;
            }
            long gained = (now - lastRefillNanos) / refillIntervalNanos;
            if (gained <= 0) {
                return;
            }
            available = (int) Math.min((long) maximum, available + gained);
            lastRefillNanos = available >= maximum
                    ? now : lastRefillNanos + gained * refillIntervalNanos;
        }

        /**
         * Gives a previously acquired slot back when a submission turns out not to produce a
         * translation (the key was already in flight, or the text held nothing translatable),
         * so the per-second budget is not spent on work that yields no output and cannot starve
         * genuinely translatable text. Never rises above the full quota.
         */
        private synchronized void refund() {
            if (available < maximum) {
                available++;
            }
        }

        private synchronized void reset() {
            lastRefillNanos = System.nanoTime();
            available = maximum;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        // Deliberately outside the monitor. coordinator.close() interrupts the workers and then waits
        // for them, and an interrupted worker settles its future synchronously -- CompletableFuture
        // runs whenComplete on the completing thread -- which lands in completeLookup and needs this
        // monitor. Holding it across the wait made every settings change wait the full five-second
        // timeout, which in turn delayed the cache flush that follows it.
        coordinator.close();
        clearRenderedTranslations();
    }

    /**
     * Releases this session without waiting for workers that are parked in a provider read.
     *
     * <p>For a session the player has already replaced. Its teardown runs on the reaper, and a burst
     * of switches queues one of these per press: paying the full five-second worker budget on each
     * one is what made the reaper's backlog last minutes. The store is still flushed, so a burst of
     * toggles cannot lose the translations that were already answered.
     */
    public void closeWithoutWaiting() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        coordinator.close(0L);
        clearRenderedTranslations();
    }

    private static final class RenderKey {
        private final String text;
        private final TextKind kind;

        private RenderKey(String text, TextKind kind) {
            this.text = text;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RenderKey)) {
                return false;
            }
            RenderKey key = (RenderKey) other;
            return text.equals(key.text) && kind == key.kind;
        }

        @Override
        public int hashCode() {
            return 31 * text.hashCode() + kind.hashCode();
        }
    }
}
