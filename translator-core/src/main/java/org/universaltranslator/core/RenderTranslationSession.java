package org.universaltranslator.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
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
            return;
        }
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

    public synchronized void clearRenderedTranslations() {
        translated.clear();
        translatedOutputs.clear();
        pending.clear();
        retryAfter.clear();
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
