package org.universaltranslator.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
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
    private static final String CACHE_FORMAT_VERSION = "translation-v5";

    private final TranslationProvider provider;
    private final TranslationStore cache;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, CompletableFuture<TranslationResult>> inFlight =
            new ConcurrentHashMap<String, CompletableFuture<TranslationResult>>();
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
        Objects.requireNonNull(text, "text");
        if (targetLanguage == null || targetLanguage.trim().isEmpty()) {
            throw new IllegalArgumentException("targetLanguage is required");
        }
        if (closed.get()) {
            return CompletableFuture.completedFuture(
                    TranslationResult.failure(text, "Translation session is closed"));
        }
        if (!LanguageHeuristics.shouldTranslate(text, targetLanguage)) {
            return CompletableFuture.completedFuture(TranslationResult.unchanged(text));
        }

        // Keep all regex construction, cache I/O and provider work off the render thread.
        // The raw request key is deliberately cheap to construct and still de-duplicates
        // the same text while a translation is in progress.
        final String requestKey = CACHE_FORMAT_VERSION + "\n" + provider.id() + "\n" + sourceLanguage + "\n"
                + targetLanguage + "\n" + kind + "\n" + text;
        CompletableFuture<TranslationResult> existing = inFlight.get(requestKey);
        if (existing == null) {
            final CompletableFuture<TranslationResult> created =
                    new CompletableFuture<TranslationResult>();
            existing = inFlight.putIfAbsent(requestKey, created);
            if (existing == null) {
                existing = created;
                try {
                    executor.execute(() -> {
                    try {
                        ProtectedText protectedText = ProtectedText.parse(
                                text, protectedLiterals, preserveHanText);
                        if (!LanguageHeuristics.shouldTranslate(
                                protectedText.getUnprotectedTemplateText(), targetLanguage)) {
                            created.complete(TranslationResult.unchanged(text));
                            return;
                        }
                        String restored = TranslationOutputValidator.requireDisplaySafe(
                                text, translateSegments(protectedText, sourceLanguage, targetLanguage, kind));
                        created.complete(TranslationResult.success(
                                text, restored));
                    } catch (Exception exception) {
                        created.complete(TranslationResult.failure(text, exception.getMessage()));
                    } finally {
                        inFlight.remove(requestKey, created);
                    }
                    });
                } catch (RejectedExecutionException busy) {
                    inFlight.remove(requestKey, created);
                    created.complete(TranslationResult.failure(
                            text, "Translation queue is busy; retrying later"));
                }
            }
        }
        return existing;
    }

    private String translateSegments(
            ProtectedText protectedText,
            String sourceLanguage,
            String targetLanguage,
            TextKind kind
    ) throws Exception {
        StringBuilder output = new StringBuilder(protectedText.getOriginal().length() + 16);
        for (ProtectedText.Segment segment : protectedText.getSegments()) {
            if (segment.isProtectedValue()) {
                output.append(segment.text());
            } else {
                output.append(translateSegment(
                        segment.text(), sourceLanguage, targetLanguage, kind));
            }
        }
        return output.toString();
    }

    private String translateSegment(
            String segment,
            String sourceLanguage,
            String targetLanguage,
            TextKind kind
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
        if (!LanguageHeuristics.shouldTranslate(core, targetLanguage)) {
            return segment;
        }
        String cacheKey = CACHE_FORMAT_VERSION + "\n" + provider.id()
                + "\n" + sourceLanguage + "\n" + targetLanguage + "\n" + core;
        String translated = cache.get(cacheKey);
        if (translated != null) {
            try {
                translated = TranslationOutputValidator.requireValid(core, translated);
            } catch (IllegalArgumentException invalidCachedValue) {
                translated = null;
            }
        }
        if (translated == null) {
            translated = provider.translate(new TranslationRequest(
                    core, sourceLanguage, targetLanguage, kind));
            if (translated == null || translated.trim().isEmpty()) {
                throw new IllegalStateException("Provider returned an empty translation");
            }
            translated = TranslationOutputValidator.requireValid(core, translated);
            cache.put(cacheKey, translated);
        }
        return segment.substring(0, start) + translated + segment.substring(end);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Complete callers before interrupting workers. Otherwise an interrupted
        // provider can win the race and publish an ordinary failure result even
        // though the whole translation session is being cancelled.
        for (CompletableFuture<TranslationResult> future : inFlight.values()) {
            future.completeExceptionally(
                    new CancellationException("Translation session was closed"));
        }
        inFlight.clear();
        executor.shutdownNow();
        if (provider instanceof AutoCloseable) {
            try {
                ((AutoCloseable) provider).close();
            } catch (Exception ignored) {
                // Minecraft is shutting down or applying a replacement configuration.
            }
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
