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
    private static final int MAX_FAILED_TRANSLATIONS = 1_024;
    // A request whose future is never completed (for example an Error escaping the coordinator's
    // worker, which only catches Exception) would otherwise hold a pending slot forever. This is
    // longer than the slowest provider read timeout (120s), so a genuinely in-flight request is
    // never reclaimed and the ordinary backpressure behaviour is preserved.
    private static final long PENDING_EXPIRY_MILLIS = 180_000L;
    private static final int MAX_BACKGROUND_SUBMISSIONS_PER_SECOND = 4;
    private static final int MAX_PRIORITY_SUBMISSIONS_PER_SECOND = 12;

    private final TranslationCoordinator coordinator;
    private final String providerCategory;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final TranslationDisplayMode displayMode;
    private final boolean preserveHanText;
    private final ConcurrentHashMap<RenderKey, String> translated = new ConcurrentHashMap<RenderKey, String>();
    private final ConcurrentHashMap<String, Boolean> translatedOutputs =
            new ConcurrentHashMap<String, Boolean>();
    // Maps an in-flight lookup to the time it was submitted, so an abandoned future can be
    // reclaimed instead of permanently consuming one of the MAX_PENDING_TRANSLATIONS slots.
    private final ConcurrentHashMap<RenderKey, Long> pending = new ConcurrentHashMap<RenderKey, Long>();
    private final ConcurrentHashMap<RenderKey, Long> retryAfter = new ConcurrentHashMap<RenderKey, Long>();
    // Insertion order for the bounded eviction of translatedOutputs and retryAfter. Keys that
    // were already evicted stay in the queue harmlessly; the remove is simply a no-op.
    private final ConcurrentLinkedQueue<String> renderedOutputOrder =
            new ConcurrentLinkedQueue<String>();
    private final ConcurrentLinkedQueue<RenderKey> retryOrder =
            new ConcurrentLinkedQueue<RenderKey>();
    private final SubmissionWindow backgroundSubmissions =
            new SubmissionWindow(MAX_BACKGROUND_SUBMISSIONS_PER_SECOND);
    private final SubmissionWindow prioritySubmissions =
            new SubmissionWindow(MAX_PRIORITY_SUBMISSIONS_PER_SECOND);
    private volatile boolean closed;
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
        this.sourceLanguage = sourceLanguage == null ? "auto" : sourceLanguage;
        if (targetLanguage == null || targetLanguage.trim().isEmpty()) {
            this.coordinator.close();
            throw new IllegalArgumentException("targetLanguage is required");
        }
        this.targetLanguage = targetLanguage.trim();
        this.displayMode = displayMode == null
                ? TranslationDisplayMode.TRANSLATED_ONLY : displayMode;
        this.preserveHanText = preserveHanText;
    }

    public String lookup(String original, TextKind kind) {
        if (closed || original == null || original.isEmpty()) {
            return original;
        }
        if (blockedKeywords.matches(original)) {
            return original;
        }
        // A GUI text can pass through both a high-level draw hook and TextRenderer.
        // Never submit our own completed output for translation a second time.
        if (isCompletedOutput(original)) {
            return original;
        }
        // Avoid building player-name snapshots for text that is already in the
        // target language or contains no words worth translating.
        if (!LanguageHeuristics.shouldTranslate(original, targetLanguage)) {
            return original;
        }
        TextKind effectiveKind = kind == null ? TextKind.OTHER : kind;
        RenderKey key = new RenderKey(original, effectiveKind);
        String ready = translated.get(key);
        if (ready != null) {
            return ready;
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
    private synchronized boolean completeLookup(
            RenderKey key,
            Long submittedAt,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        // Always run, even when the session is closing: a pending entry that is never
        // removed permanently consumes one of the MAX_PENDING_TRANSLATIONS slots. The value
        // check keeps a late completion from clearing a newer submission of the same key.
        pending.remove(key, submittedAt);
        if (closed) {
            return false;
        }
        // The filter can be replaced while an older request is still completing.
        // Never publish a result that is blocked by the current configuration.
        if (blockedKeywords.matches(original)) {
            retryAfter.remove(key);
            return false;
        }
        if (error != null || result == null || result.isFailure()) {
            // Bound the insertion order queue rather than the map: keys can leave the map on a
            // later success, so only the queue length reflects the true retained history.
            if (retryOrder.size() >= MAX_FAILED_TRANSLATIONS) {
                // Evict the oldest entries instead of clearing every key: a full clear makes
                // all failed strings retryable at the same moment and produces a retry burst.
                evictOldestFailedTranslations(MAX_FAILED_TRANSLATIONS / 8);
            }
            if (retryAfter.put(key, System.currentTimeMillis() + FAILURE_RETRY_MILLIS) == null) {
                retryOrder.add(key);
            }
            lastFailureStatus = safeFailureStatus(error, result);
            reportFailureIfChanged(lastFailureStatus);
            return false;
        }
        lastFailureStatus = "";
        retryAfter.remove(key);
        if (result.isTranslated()) {
            if (translated.size() >= MAX_RENDERED_TRANSLATIONS) {
                // Only the translation map is evicted. translatedOutputs is the re-entry
                // guard that stops the mod from translating its own rendered output; losing
                // it would re-submit every evicted line (and compound the bilingual prefix).
                translated.clear();
            }
            if (translatedOutputs.size() >= MAX_RENDERED_OUTPUTS) {
                evictOldestRenderedOutputs(MAX_RENDERED_OUTPUTS / 8);
            }
            String output = formatOutput(original, result.getTranslatedText());
            translated.put(key, output);
            rememberRenderedOutput(output);
            rememberRenderedOutput(TranslationTextStyling.stripLegacyFormatting(output));
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
        // Bilingual formatting inserts the original and translated text together.
        // Preserve the established one-output-line-per-input-line behavior in that mode.
        if (displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED) {
            List<String> replacement = new ArrayList<String>(originals.size());
            boolean changed = false;
            for (String original : originals) {
                String translated = lookup(original, kind);
                replacement.add(translated);
                changed |= original == null ? translated != null : !original.equals(translated);
            }
            return changed ? replacement : originals;
        }
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < originals.size(); index++) {
            String line = originals.get(index);
            if (line == null || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                return originals;
            }
            if (index > 0) {
                joined.append('\n');
            }
            joined.append(line);
        }
        String originalText = joined.toString();
        String translatedText = lookup(originalText, kind);
        if (originalText.equals(translatedText)) {
            return originals;
        }
        String[] translatedLines = translatedText.split("\\n", -1);
        if (translatedLines.length != originals.size()) {
            return originals;
        }
        List<String> replacement = new ArrayList<String>(translatedLines.length);
        Collections.addAll(replacement, translatedLines);
        return replacement;
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
        return coordinator.translate(
                original,
                sourceLanguage,
                target,
                kind == null ? TextKind.CHAT : kind,
                literals,
                preserveHanText);
    }

    private boolean isCompletedOutput(String text) {
        if (translatedOutputs.containsKey(text)) {
            return true;
        }
        String unformatted = TranslationTextStyling.stripLegacyFormatting(text);
        return unformatted != text && translatedOutputs.containsKey(unformatted);
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
        this.blockedKeywords = TranslationBlocklist.parse(configuredKeywords);
        clearRenderedTranslations();
    }

    /** Latest render-time failure, cleared after the next successful request. */
    public String lastFailureStatus() {
        return lastFailureStatus;
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

    private void reportFailureIfChanged(String status) {
        if (status.equals(lastReportedFailureStatus)) {
            return;
        }
        lastReportedFailureStatus = status;
        // Minecraft captures stderr in latest.log. Log only the provider identifier and the
        // sanitized status: never the source text, request body, endpoint path, or credentials.
        System.err.println("[MC Auto Translation Tool] provider=" + providerCategory + " " + status);
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

    public synchronized void clearRenderedTranslations() {
        translated.clear();
        translatedOutputs.clear();
        renderedOutputOrder.clear();
        pending.clear();
        retryAfter.clear();
        retryOrder.clear();
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
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
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
