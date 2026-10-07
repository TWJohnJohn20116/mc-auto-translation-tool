package org.universaltranslator.core;

import java.util.List;
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

        final String effectiveSource = sourceLanguage == null ? "auto" : sourceLanguage.trim();
        final TextKind effectiveKind = kind == null ? TextKind.OTHER : kind;

        // Keep all regex construction, cache I/O and provider work off the render thread.
        // The protected-literal snapshot is identified by its contents so that requests with
        // different player-name snapshots cannot accidentally share an in-flight result, while
        // requests that only differ in the list instance still de-duplicate.
        final String requestKey = CACHE_FORMAT_VERSION + "\n" + provider.id() + "\n" + effectiveSource + "\n"
                + targetLanguage + "\n" + effectiveKind + "\n" + preserveHanText + "\n"
                + protectedLiteralsSignature(protectedLiterals) + "\n" + text;
        CompletableFuture<TranslationResult> existing = inFlight.get(requestKey);
        if (existing == null) {
            final CompletableFuture<TranslationResult> created =
                    new CompletableFuture<TranslationResult>();
            existing = inFlight.putIfAbsent(requestKey, created);
            if (existing == null) {
                existing = created;
                // Only work that is really handed to the provider is counted. Every other exit
                // above returns a future that performs no provider work (closed session, nothing
                // to translate, or losing the putIfAbsent race to a thread that already counted
                // this request), and none of those may light the HUD indicator.
                // begin() sits before executor.execute(...) so a worker can never settle this
                // future - and therefore run the end() below - before the count went up.
                TranslationActivity.begin();
                // Side-effect only: whenComplete returns a NEW future, which is deliberately
                // discarded so the future handed back to callers keeps its identity and its
                // original exception semantics. whenComplete (not thenRun) so failures,
                // cancellation and close() decrement too.
                created.whenComplete((result, failure) -> TranslationActivity.end());
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
                                text, translateSegments(protectedText, effectiveSource, targetLanguage, effectiveKind));
                        created.complete(TranslationResult.success(
                                text, restored));
                    } catch (Exception exception) {
                        created.complete(TranslationResult.failure(text, failureMessage(exception)));
                    } catch (Throwable fatal) {
                        // LinkageError/UnsatisfiedLinkError from the offline native library,
                        // OutOfMemoryError and StackOverflowError are not Exception subtypes.
                        // Complete the future first so callers never leak an in-flight entry,
                        // then let the fatal error propagate and kill the worker thread.
                        created.complete(TranslationResult.failure(text, failureMessage(fatal)));
                        throw fatal;
                    } finally {
                        inFlight.remove(requestKey, created);
                    }
                    });
                } catch (RejectedExecutionException busy) {
                    inFlight.remove(requestKey, created);
                    created.complete(TranslationResult.failure(
                            text, "Translation queue is busy; retrying later"));
                } catch (Throwable fatal) {
                    // begin() already ran; without this the count would never come back down and the
                    // HUD indicator would be stuck showing "translating" for the rest of the session.
                    TranslationActivity.end();
                    throw fatal;
                }
            }
        }
        return existing;
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
                + "\n" + sourceLanguage + "\n" + targetLanguage + "\n" + kind + "\n" + core;
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
        // Wait for interrupted workers to leave provider.translate(...) before the provider
        // is torn down. Otherwise a worker can loop back into a retrying provider (for
        // example ResilientTranslationProvider retrying an IOException) against an
        // already-closed endpoint, the offline provider kills its child process under a
        // live loopback call, and a replacement session races this one on the same
        // cache .tmp file. Proceed after the bounded wait so a stuck provider cannot
        // hang Minecraft shutdown.
        try {
            if (!executor.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                System.err.println("[MC Auto Translation Tool] translation workers did not stop within "
                        + WORKER_SHUTDOWN_TIMEOUT_MILLIS + "ms; closing provider anyway");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (provider instanceof AutoCloseable) {
            try {
                ((AutoCloseable) provider).close();
            } catch (Exception ignored) {
                // Minecraft is shutting down or applying a replacement configuration.
            }
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
