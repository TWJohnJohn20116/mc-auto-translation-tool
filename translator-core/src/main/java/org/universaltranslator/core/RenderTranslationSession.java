package org.universaltranslator.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
    private static final int MAX_BACKGROUND_SUBMISSIONS_PER_SECOND = 4;
    private static final int MAX_PRIORITY_SUBMISSIONS_PER_SECOND = 12;

    private final TranslationCoordinator coordinator;
    private final String providerCategory;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final TranslationDisplayMode displayMode;
    private final boolean preserveHanText;
    /** Whether {@code targetLanguage} is Chinese; resolved once because the render path asks per string. */
    private final boolean chineseTarget;
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
    // Single-slot memo for the tooltip join. Screen.getTooltipFromItem calls the line path once per
    // frame with the same list instance, so without this every frame built a fresh ~1 KB String and
    // re-hashed it inside the lookup key. Reuse is decided by element identity, so a list that really
    // changed can never serve a stale join.
    private volatile List<String> joinedLinesSource;
    private volatile String joinedLinesText;
    /**
     * Bumped whenever a translation is published or the tables are cleared. A line-path memo is only
     * valid for the generation it was built in: between two generations the {@code translated} map
     * can only have grown, so a remembered output is still the answer, while a changed generation
     * means a line that used to fall back to its original may now be translated.
     */
    private volatile long publishGeneration;
    /** Finished output of {@link #lookupLines} in translated-only mode, if the last call had one. */
    private volatile LinesMemo translatedLinesMemo;
    /** Finished output of {@link #lookupLines} in bilingual mode, when every line was translated. */
    private volatile LinesMemo bilingualLinesMemo;
    /**
     * Single-slot identity memo for the formatting-stripped form used by the re-entry guard. Only
     * consulted for text that carries a formatting code, which is exactly the text whose strip would
     * otherwise allocate a copy of the whole line on the render thread every frame.
     */
    private volatile String strippedSource;
    private volatile String strippedResult;
    private volatile String lastFailureStatus = "";
    private volatile String lastReportedFailureStatus = "";
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
            // second time and, in bilingual mode, comes back with a second prefix. Remembering it
            // again is one hash lookup, and only when the entry is really missing.
            rememberRenderedOutput(ready);
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
        // target language or contains no words worth translating.
        if (!LanguageHeuristics.shouldTranslateToTarget(original, chineseTarget)) {
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
            request = coordinator.translate(original, sourceLanguage, targetLanguage, effectiveKind,
                    literals, preserveHanText);
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
     * Settles one lookup, and prints a changed failure status only after leaving the monitor.
     *
     * <p>The monitor is shared with {@link #clearRenderedTranslations()}, {@link #close()} and
     * {@link #setBlockedKeywords}, so nothing inside it may block. A stderr write can: latest.log is
     * flushed on write and a full console pipe blocks the writer for milliseconds. The line is
     * therefore formatted under the monitor and printed outside it.
     */
    private boolean completeLookup(
            RenderKey key,
            Long submittedAt,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        FailureReport report = new FailureReport();
        boolean changed = completeLookupLocked(key, submittedAt, original, result, error, report);
        report.print();
        return changed;
    }

    private synchronized boolean completeLookupLocked(
            RenderKey key,
            Long submittedAt,
            String original,
            TranslationResult result,
            Throwable error,
            FailureReport report
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
            lastFailureStatus = safeFailureStatus(error, result);
            report.line = failureReportLineIfChanged(lastFailureStatus);
            return false;
        }
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
            String output = formatOutput(original, result.getTranslatedText());
            if (translated.put(key, output) == null) {
                renderedTranslationOrder.add(key);
            }
            rememberRenderedOutput(output);
            rememberStrippedVariant(output);
            // A new translation invalidates every remembered line-path result.
            publishGeneration++;
            return true;
        }
        return false;
    }

    /**
     * Drops pending entries whose future was never completed, so the map cannot stay wedged at
     * its cap. Only runs when the map is full, so the scan is rare and never on the common path.
     */
    private void reclaimExpiredPending(long now) {
        long cutoff = now - PENDING_EXPIRY_MILLIS;
        for (Map.Entry<RenderKey, Long> entry : pending.entrySet()) {
            Long submittedAt = entry.getValue();
            if (submittedAt != null && submittedAt.longValue() <= cutoff) {
                pending.remove(entry.getKey(), submittedAt);
            }
        }
    }

    private void rememberRenderedOutput(String output) {
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
     */
    public List<String> lookupLines(List<String> originals, TextKind kind) {
        if (originals == null || originals.isEmpty()) {
            return originals;
        }
        TextKind effectiveKind = kind == null ? TextKind.OTHER : kind;
        // Read once: a publish between the memo test and the rebuild would otherwise be folded into
        // a memo that claims to be older than it is.
        long generation = publishGeneration;
        // Bilingual formatting inserts the original and translated text together.
        // Preserve the established one-output-line-per-input-line behavior in that mode.
        if (displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED) {
            LinesMemo memo = bilingualLinesMemo;
            if (memo != null && memo.matches(originals, effectiveKind, generation)) {
                return memo.output();
            }
            List<String> replacement = new ArrayList<String>(originals.size());
            boolean changed = false;
            boolean complete = true;
            for (String original : originals) {
                String line = lookup(original, effectiveKind);
                replacement.add(line);
                boolean lineChanged = original == null ? line != null : !original.equals(line);
                changed |= lineChanged;
                complete &= lineChanged;
            }
            if (!changed) {
                return originals;
            }
            // Only a fully translated list is remembered: while one line is still pending the next
            // frame has to keep asking for it, because that lookup is what re-submits the retry and
            // eventually publishes the line.
            if (complete) {
                bilingualLinesMemo = new LinesMemo(originals, effectiveKind, generation, replacement);
            }
            return replacement;
        }
        // The whole result is remembered, not just the join: a hit means the joined line is already
        // translated, so splitting it again only rebuilt n substrings for a list that cannot have
        // changed since the last frame.
        LinesMemo memo = translatedLinesMemo;
        if (memo != null && memo.matches(originals, effectiveKind, generation)) {
            return memo.output();
        }
        String originalText = joinedLines(originals);
        if (originalText == null) {
            return originals;
        }
        String translatedText = lookup(originalText, effectiveKind);
        if (originalText.equals(translatedText)) {
            return originals;
        }
        List<String> translatedLines = splitLines(translatedText);
        if (translatedLines.size() != originals.size()) {
            return originals;
        }
        translatedLinesMemo = new LinesMemo(originals, effectiveKind, generation, translatedLines);
        return translatedLines;
    }

    /**
     * One remembered {@link #lookupLines} result: the input lines, the kind they were looked up with,
     * the publish generation they were built in, and the list to hand back.
     *
     * <p>Immutable and replaced as a whole, so a reader can never observe a source from one frame next
     * to an output from another. The output is kept unmodifiable because it is now handed to several
     * frames instead of one.
     */
    private static final class LinesMemo {
        private final List<String> source;
        private final TextKind kind;
        private final long generation;
        private final List<String> output;

        private LinesMemo(List<String> source, TextKind kind, long generation, List<String> output) {
            this.source = source;
            this.kind = kind;
            this.generation = generation;
            this.output = Collections.unmodifiableList(new ArrayList<String>(output));
        }

        private boolean matches(List<String> current, TextKind currentKind, long currentGeneration) {
            return kind == currentKind && generation == currentGeneration
                    && sameLines(source, current);
        }

        private List<String> output() {
            return output;
        }
    }

    /**
     * Joins the lines with {@code '\n'}, reusing the previous result when the same lines are passed
     * again, and returns {@code null} when one line cannot survive the round trip.
     */
    private String joinedLines(List<String> originals) {
        String previous = joinedLinesText;
        if (previous != null && sameLines(joinedLinesSource, originals)) {
            return previous;
        }
        StringBuilder joined = new StringBuilder(originals.size() * 32);
        for (int index = 0; index < originals.size(); index++) {
            String line = originals.get(index);
            if (line == null || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                return null;
            }
            if (index > 0) {
                joined.append('\n');
            }
            joined.append(line);
        }
        String text = joined.toString();
        joinedLinesSource = originals;
        joinedLinesText = text;
        return text;
    }

    /**
     * Whether {@code cached} holds the same lines as {@code current}.
     *
     * <p>Content, not identity: the tooltip path rebuilds its list from the components on every
     * frame, so an identity check would never hit. Equal lines always join to the same text, so a
     * content match can never serve a stale join.
     */
    private static boolean sameLines(List<String> cached, List<String> current) {
        return cached != null && cached.equals(current);
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
        String unformatted = strippedVariant(text);
        return unformatted != text && translatedOutputs.containsKey(unformatted);
    }

    /** Remembers the formatting-stripped form of {@code output} as a completed output too. */
    private void rememberStrippedVariant(String output) {
        if (output.indexOf('\u00a7') < 0) {
            return;
        }
        rememberRenderedOutput(strippedVariant(output));
    }

    /**
     * The formatting-stripped form of {@code text}, remembered by identity.
     *
     * <p>{@code TranslationTextStyling.stripLegacyFormatting} builds a StringBuilder and copies the
     * line character by character whenever a formatting code is present, and the re-entry guard asked
     * for that copy on every frame for a text that is not yet known to be one of our outputs. Identity
     * is enough to reuse the answer because a {@link String} never changes its contents.
     */
    private String strippedVariant(String text) {
        if (strippedSource == text) {
            return strippedResult;
        }
        String stripped = TranslationTextStyling.stripLegacyFormatting(text);
        strippedResult = stripped;
        strippedSource = text;
        return stripped;
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
        while (current != null) {
            if (current instanceof TranslationCoordinator.SessionClosedException) {
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
            singleLine = singleLine.substring(0, 117) + "...";
        }
        return "翻译失败：" + singleLine;
    }

    /** Carries the failure line from inside the session monitor to the print that follows it. */
    private static final class FailureReport {
        private String line;

        private void print() {
            if (line == null) {
                return;
            }
            // Minecraft captures stderr in latest.log. Log only the provider identifier and the
            // sanitized status: never the source text, request body, endpoint path, or credentials.
            System.err.println(line);
        }
    }

    /**
     * The line to print for {@code status}, or {@code null} when this status was already reported.
     *
     * <p>Must be called under the session monitor: it updates {@code lastReportedFailureStatus}.
     */
    private String failureReportLineIfChanged(String status) {
        if (status.equals(lastReportedFailureStatus)) {
            return null;
        }
        lastReportedFailureStatus = status;
        return "[MC Auto Translation Tool] provider=" + providerCategory + " " + status;
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
        while (rejected.size() > MAX_REJECTED_TEXTS) {
            RenderKey oldest = rejectedOrder.poll();
            if (oldest == null) {
                break;
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
        publishGeneration++;
        translatedLinesMemo = null;
        bilingualLinesMemo = null;
        joinedLinesSource = null;
        joinedLinesText = null;
        lastFailureStatus = "";
        lastReportedFailureStatus = "";
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

    private static final class SubmissionWindow {
        private final int maximum;
        private long windowStartedAt;
        private int used;

        private SubmissionWindow(int maximum) {
            this.maximum = maximum;
        }

        private synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            if (windowStartedAt == 0L || now - windowStartedAt >= TimeUnit.SECONDS.toNanos(1L)) {
                windowStartedAt = now;
                used = 0;
            }
            if (used >= maximum) {
                return false;
            }
            used++;
            return true;
        }

        /**
         * Gives a previously acquired slot back when a submission turns out not to produce a
         * translation (the key was already in flight, or the text held nothing translatable),
         * so the per-second budget is not spent on work that yields no output and cannot starve
         * genuinely translatable text. Never drops below zero.
         */
        private synchronized void refund() {
            if (used > 0) {
                used--;
            }
        }

        private synchronized void reset() {
            windowStartedAt = 0L;
            used = 0;
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
