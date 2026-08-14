package org.universaltranslator.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
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
    private static final int MAX_FAILED_TRANSLATIONS = 1_024;
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
    private final ConcurrentHashMap<RenderKey, Boolean> pending = new ConcurrentHashMap<RenderKey, Boolean>();
    private final ConcurrentHashMap<RenderKey, Long> retryAfter = new ConcurrentHashMap<RenderKey, Long>();
    private final SubmissionWindow backgroundSubmissions =
            new SubmissionWindow(MAX_BACKGROUND_SUBMISSIONS_PER_SECOND);
    private final SubmissionWindow prioritySubmissions =
            new SubmissionWindow(MAX_PRIORITY_SUBMISSIONS_PER_SECOND);
    private volatile boolean closed;
    private volatile String lastFailureStatus = "";
    private volatile String lastReportedFailureStatus = "";
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
            return original;
        }
        if (pending.containsKey(key)) {
            return original;
        }
        // A global font hook can see hundreds of unique labels per second in a lobby.
        // Keep the local model from running at 100% continuously. Interactive and HUD
        // surfaces use a separate allowance so tooltips and chat are not starved by
        // world-space labels.
        if (!submissionWindow(effectiveKind).tryAcquire()) {
            return original;
        }
        if (pending.putIfAbsent(key, Boolean.TRUE) == null) {
            Iterable<String> literals;
            try {
                literals = protectedLiterals.get();
            } catch (RuntimeException ignored) {
                literals = Collections.emptyList();
            }
            coordinator.translate(original, sourceLanguage, targetLanguage, effectiveKind,
                            literals, preserveHanText)
                    .whenComplete((result, error) -> completeLookup(
                            key, original, result, error));
        }
        return original;
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
    private synchronized void completeLookup(
            RenderKey key,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        pending.remove(key);
        if (closed) {
            return;
        }
        if (error != null || result == null || result.isFailure()) {
            if (retryAfter.size() >= MAX_FAILED_TRANSLATIONS) {
                retryAfter.clear();
            }
            retryAfter.put(key, System.currentTimeMillis() + FAILURE_RETRY_MILLIS);
            lastFailureStatus = safeFailureStatus(error, result);
            reportFailureIfChanged(lastFailureStatus);
            return;
        }
        lastFailureStatus = "";
        retryAfter.remove(key);
        if (result.isTranslated()) {
            if (translated.size() >= MAX_RENDERED_TRANSLATIONS) {
                translated.clear();
                translatedOutputs.clear();
            }
            String output = formatOutput(original, result.getTranslatedText());
            translated.put(key, output);
            translatedOutputs.put(output, Boolean.TRUE);
            translatedOutputs.put(
                    TranslationTextStyling.stripLegacyFormatting(output), Boolean.TRUE);
        }
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
        pending.clear();
        retryAfter.clear();
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
