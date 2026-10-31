package org.universaltranslator.core;

import org.universaltranslator.core.net.HttpStatusException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide translation counters, persisted next to the configuration.
 *
 * <p>Everything here is cheap and lock-bounded: one counter update per provider request, plus a
 * bounded ring of latency samples. Nothing touches the disk on the translation path — the file is
 * read once when the platform attaches it and written by a background flusher and by a shutdown
 * hook, so a slow or unwritable configuration directory can never delay a translation.
 *
 * <p>The counters are process-wide rather than per-coordinator because a platform rebuilds its
 * provider and coordinator whenever the settings change, and a user counting requests does not
 * expect the count to reset each time a switch is flipped.
 */
public final class TranslationStats {
    /** Sits next to {@code universal-translator.properties} in the game instance's config folder. */
    public static final String FILE_NAME = "universal-translator-stats.properties";
    /** Latency samples kept for the percentiles; the oldest sample is overwritten first. */
    private static final int LATENCY_SAMPLE_CAPACITY = 512;
    /** How often the counters reach the disk while the game runs. */
    private static final long FLUSH_INTERVAL_MILLIS = 60_000L;
    /** Bound on the provider ids read back from the file, so a corrupt file cannot grow memory. */
    private static final int MAXIMUM_STORED_PROVIDERS = 64;
    /** Bucket that takes every provider id arriving after {@link #MAXIMUM_STORED_PROVIDERS}. */
    private static final String PROVIDER_OVERFLOW = "other";
    private static final String PROVIDER_PREFIX = "provider.";
    private static final String FAILURE_PREFIX = "failure.";
    /** Longs {@link #captureProviderFields} copies per provider, in {@link ProviderLine} order. */
    private static final int PROVIDER_FIELDS = 7;

    public static final String REASON_AUTH = "auth";
    public static final String REASON_RATE_LIMIT = "rate-limit";
    public static final String REASON_SERVER = "server";
    public static final String REASON_CLIENT = "client";
    public static final String REASON_TIMEOUT = "timeout";
    public static final String REASON_NETWORK = "network";
    public static final String REASON_INVALID_OUTPUT = "invalid-output";
    public static final String REASON_OTHER = "other";

    private static final TranslationStats GLOBAL = new TranslationStats();

    private final Object lock = new Object();
    private final Map<String, ProviderCounters> providers = new LinkedHashMap<String, ProviderCounters>();
    private final Map<String, Long> failuresByReason = new LinkedHashMap<String, Long>();
    private final long[] latencySamples = new long[LATENCY_SAMPLE_CAPACITY];
    private int latencyCursor;
    private int latencyCount;
    private long requests;
    private long successes;
    private long failures;
    private long cacheHits;
    private long cacheMisses;
    private long promptTokens;
    private long completionTokens;
    private long rejected;
    /**
     * Bumped by every counter update. {@link #snapshot()} rebuilds its immutable view only when
     * this moved, so a diagnostics screen that redraws every frame reuses one snapshot instead of
     * re-boxing 512 latencies and re-sorting them sixty times a second.
     */
    private long revision;
    private Snapshot cachedSnapshot;
    private long cachedRevision = -1L;
    /**
     * The localized lines and the revision they were built from. The diagnostics screens ask for
     * them from {@code render()}, so resolving the language keys and formatting the numbers again
     * for an unchanged view was the same per-frame cost {@link #snapshot()} already avoids.
     */
    private List<String> cachedLines;
    private long cachedLinesRevision = -1L;
    /**
     * Guarded by the path monitor of {@code file}, not by {@link #lock}: the newest {@link #revision}
     * whose counters reached the disk. A flush that took its snapshot before a {@link #reset()} must
     * not write those pre-reset totals back over the zeros.
     */
    private long writtenRevision = -1L;
    private volatile Path file;
    private volatile boolean flusherStarted;

    private TranslationStats() {
    }

    /** The counters every platform shares. */
    public static TranslationStats global() {
        return GLOBAL;
    }

    /**
     * A fresh accumulator that is not the process-wide one.
     *
     * <p>Only the dependency-free self test uses this: the counters have to be exercised without
     * disturbing the process-wide instance, which is attached to a real configuration directory
     * exactly once per launch.
     *
     * @return an unattached accumulator
     */
    static TranslationStats isolated() {
        return new TranslationStats();
    }

    /**
     * Points the counters at their file, reading it once and starting the periodic flusher.
     *
     * <p>Called by every platform while it loads its configuration, so the totals survive a
     * restart. A missing, empty or malformed file simply starts the counters at zero.
     *
     * @param statisticsFile file to read and later write; {@code null} does nothing
     */
    public void attach(Path statisticsFile) {
        if (statisticsFile == null) {
            return;
        }
        synchronized (lock) {
            if (file != null && file.equals(statisticsFile)) {
                return;
            }
        }
        // A second attach with a different file used to be dropped silently, so every later total
        // went to the first path while the platform displayed the second. Writing the old file
        // before the switch keeps what was counted under it instead of abandoning it.
        flush();
        boolean replaced;
        synchronized (lock) {
            replaced = file != null;
            file = statisticsFile;
        }
        if (replaced) {
            DebugLog.global().log("stats-attach", "result", "replaced");
        }
        // Same cleanup point as reset(): the display lines cached from the previous file or from the
        // previous language must not survive the switch.
        invalidateLocalizedLines();
        load(statisticsFile);
        startFlusher();
    }

    /**
     * Records that a translation was answered from the cache, without touching the provider.
     *
     * <p>Called once per answer, from the same place the provider success is counted: a lookup
     * whose request is later rejected must not move the hit rate, otherwise the ratio is computed
     * from two differently-scoped populations.
     */
    public void recordCacheHit() {
        recordCacheHits(1);
    }

    /**
     * Records several cache answers at once, for a request that only settles its counters when its
     * restored text is accepted.
     *
     * @param count number of cache answers; values below one do nothing
     */
    public void recordCacheHits(int count) {
        if (count <= 0) {
            return;
        }
        synchronized (lock) {
            cacheHits += count;
            revision++;
        }
    }

    /** Records that a translation had to be requested from the provider. */
    public void recordCacheMiss() {
        recordCacheMisses(1);
    }

    /**
     * Records several provider lookups at once; see {@link #recordCacheHits(int)}.
     *
     * @param count number of lookups that missed the cache; values below one do nothing
     */
    public void recordCacheMisses(int count) {
        if (count <= 0) {
            return;
        }
        synchronized (lock) {
            cacheMisses += count;
            revision++;
        }
    }

    /**
     * Records one request whose result was thrown away because the assembled text failed the
     * display check.
     *
     * <p>Kept apart from {@link #recordFailure} on purpose: the provider answered, so the request
     * is not a provider failure, but it also produced nothing usable. Without this counter the
     * exported report showed cache lookups that no request could be matched against.
     */
    public void recordRejected() {
        synchronized (lock) {
            // Counted as a request: a rejected translation was asked for, and every path that
            // settles a request has to contribute exactly one entry here or the identity the
            // report prints (requests == successes + failures + rejected) can never hold.
            requests++;
            rejected++;
            revision++;
        }
    }

    /**
     * Records one provider request that produced a translation.
     *
     * @param providerId provider id, or {@code null} for the unknown bucket
     * @param latencyMillis wall-clock time the request took
     */
    public void recordSuccess(String providerId, long latencyMillis) {
        synchronized (lock) {
            requests++;
            successes++;
            ProviderCounters counters = counters(providerId);
            counters.requests++;
            counters.successes++;
            counters.latencySum += latencyMillis;
            counters.latencyCount++;
            addLatencySample(latencyMillis);
            revision++;
        }
    }

    /**
     * Records one provider request that failed, classified by {@link #reasonOf}.
     *
     * @param providerId provider id, or {@code null} for the unknown bucket
     * @param reason reason key from {@link #reasonOf}
     * @param latencyMillis wall-clock time the request took before failing
     */
    public void recordFailure(String providerId, String reason, long latencyMillis) {
        String key = reason == null || reason.isEmpty() ? REASON_OTHER : reason;
        synchronized (lock) {
            requests++;
            failures++;
            Long previous = failuresByReason.get(key);
            failuresByReason.put(key, Long.valueOf(previous == null ? 1L : previous.longValue() + 1L));
            ProviderCounters counters = counters(providerId);
            counters.requests++;
            counters.failures++;
            counters.latencySum += latencyMillis;
            counters.latencyCount++;
            addLatencySample(latencyMillis);
            revision++;
        }
    }

    /**
     * Reads the token counts a provider reported and adds them to the totals.
     *
     * <p>The three protocols name the same numbers differently — OpenAI and Claude use
     * {@code usage.prompt_tokens} / {@code usage.input_tokens}, Gemini uses
     * {@code usageMetadata.promptTokenCount} — so all of them are accepted here instead of making
     * every provider carry its own bookkeeping.
     *
     * @param providerId provider id, or {@code null} for the unknown bucket
     * @param responseBody raw response body, possibly {@code null}
     * @deprecated only for a caller that has no parsed body. A provider that already parsed the
     *     response pays for a second full parse of a multi-kilobyte batch answer here, so it should
     *     hand its parsed root to {@link #recordParsedUsage(String, Object)}.
     */
    @Deprecated
    public void recordUsage(String providerId, String responseBody) {
        if (responseBody == null) {
            return;
        }
        Object root;
        try {
            root = org.universaltranslator.core.net.JsonStrings.parse(responseBody);
        } catch (RuntimeException malformed) {
            return;
        }
        recordParsedUsage(providerId, root);
    }

    /**
     * Adds the token counts of a response a provider has already parsed.
     *
     * <p>Every LLM provider parses the body into a map before it can take the translation out of it,
     * so a provider holding that map can hand it over here and pay for one parse instead of two. The
     * protocol-specific names stay in this class instead of being repeated by every provider.
     *
     * @param providerId provider id, or {@code null} for the unknown bucket
     * @param parsedRoot the body as returned by {@code JsonStrings.parse}; anything that is not a map,
     *     or carries no token counts, adds nothing
     */
    public void recordParsedUsage(String providerId, Object parsedRoot) {
        if (!(parsedRoot instanceof Map)) {
            return;
        }
        Map<?, ?> object = (Map<?, ?>) parsedRoot;
        long prompt = 0L;
        long completion = 0L;
        Object usage = object.get("usage");
        if (usage instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) usage;
            prompt = number(values.get("prompt_tokens"), values.get("input_tokens"));
            completion = number(values.get("completion_tokens"), values.get("output_tokens"));
        }
        Object metadata = object.get("usageMetadata");
        if (metadata instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) metadata;
            prompt = number(values.get("promptTokenCount"), null);
            completion = number(values.get("candidatesTokenCount"), null);
        }
        recordUsage(providerId, prompt, completion);
    }

    /**
     * Adds token counts a provider has already read out of its own response.
     *
     * <p>Every LLM provider parses the body into a map before it can take the translation out of
     * it, so handing the same string back to this class made each request pay for a second full
     * parse of a multi-kilobyte batch response at the end of its critical path.
     *
     * @param providerId provider id, or {@code null} for the unknown bucket
     * @param promptTokens prompt tokens the provider reported; negative values count as zero
     * @param completionTokens completion tokens the provider reported; negative values count as zero
     */
    public void recordUsage(String providerId, long promptTokens, long completionTokens) {
        if (promptTokens <= 0L && completionTokens <= 0L) {
            return;
        }
        long reportedPrompt = Math.max(0L, promptTokens);
        long reportedCompletion = Math.max(0L, completionTokens);
        synchronized (lock) {
            this.promptTokens += reportedPrompt;
            this.completionTokens += reportedCompletion;
            ProviderCounters counters = counters(providerId);
            counters.promptTokens += reportedPrompt;
            counters.completionTokens += reportedCompletion;
            revision++;
        }
    }

    /** Clears every counter, including the persisted totals. */
    public void reset() {
        synchronized (lock) {
            providers.clear();
            failuresByReason.clear();
            latencyCursor = 0;
            latencyCount = 0;
            requests = 0L;
            successes = 0L;
            failures = 0L;
            cacheHits = 0L;
            cacheMisses = 0L;
            rejected = 0L;
            promptTokens = 0L;
            completionTokens = 0L;
            revision++;
        }
        invalidateLocalizedLines();
        flush();
    }

    /**
     * Serializes the writers of one statistics file, keyed by its absolute path.
     *
     * <p>Three unrelated callers reach {@link #flush()}: the periodic flusher thread, the JVM
     * shutdown hook and the "reset statistics" button on every diagnostics screen. They used to
     * write through one fixed {@code .tmp} name with no mutual exclusion at all, so two overlapping
     * flushes truncated each other's file and the recorded totals were silently lost.
     */
    private static final ConcurrentHashMap<String, Object> PATH_LOCKS =
            new ConcurrentHashMap<String, Object>();

    /**
     * Writes the counters out. Never throws: statistics must not be able to break a translation.
     *
     * <p>The counters are read before the path monitor is taken, so the revision they were read at
     * travels into that monitor with them: a flush that snapshotted before a {@link #reset()} finds
     * a newer {@link #writtenRevision} and gives up instead of putting the deleted totals back.
     */
    public void flush() {
        Path target = file;
        if (target == null) {
            return;
        }
        Properties properties;
        long snapshotRevision;
        synchronized (lock) {
            snapshotRevision = revision;
            properties = toProperties();
        }
        synchronized (lockFor(target)) {
            if (snapshotRevision < writtenRevision) {
                return;
            }
            Path temporary = null;
            try {
                // A unique name per attempt: a shared ".tmp" is what allowed one writer to truncate
                // the file another writer was still filling.
                temporary = target.resolveSibling(target.getFileName().toString() + "."
                        + UUID.randomUUID() + ".tmp");
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    properties.store(writer, "MC Auto Translation Tool - translation statistics");
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                temporary = null;
                writtenRevision = snapshotRevision;
            } catch (IOException | RuntimeException failed) {
                // Not a translation failure, but no longer invisible: a statistics file that never
                // lands used to be indistinguishable from one that was simply not due yet.
                DebugLog.global().logFailure("stats-flush", failed);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // Best effort only: the name is unique, so a leftover is never reused.
                    }
                }
            }
        }
    }

    /** The monitor that serializes every writer of {@code target}, process-wide. */
    private static Object lockFor(Path target) {
        String key = target.toAbsolutePath().normalize().toString();
        // computeIfAbsent rather than putIfAbsent: the eager construction allocated a monitor on
        // every flush that was then thrown away. Entries are never removed, which is what keeps the
        // returned monitor stable for the life of the process.
        return PATH_LOCKS.computeIfAbsent(key, ignored -> new Object());
    }

    /**
     * An immutable view of the counters, safe to hand to a render thread.
     *
     * <p>The diagnostics screens ask for this from {@code render()}, i.e. sixty times a second while
     * they are open, but the counters only change when a provider call settles. The view is
     * therefore reused until a counter update bumps {@link #revision}; an idle screen then costs one
     * integer test under the workers' lock, and a rebuild copies no more than the primitives it
     * needs before leaving that lock.
     */
    public Snapshot snapshot() {
        long builtRevision;
        long requestTotal;
        long successTotal;
        long failureTotal;
        long hitTotal;
        long missTotal;
        long rejectedTotal;
        long promptTotal;
        long completionTotal;
        String[] reasonKeys;
        long[] reasonValues;
        String[] providerIds;
        long[] providerFields;
        int sampleCount;
        synchronized (lock) {
            Snapshot cached = cachedSnapshot;
            if (cached != null && cachedRevision == revision) {
                return cached;
            }
            // Only the state the workers own is copied out here, into arrays of primitives and
            // string references. The view itself -- the boxed reason map, the provider lines and the
            // sample copy -- is assembled below, outside the monitor, so a diagnostics screen that
            // asks while translations are running no longer stops every worker for the length of
            // that boxing and object allocation.
            builtRevision = revision;
            requestTotal = requests;
            successTotal = successes;
            failureTotal = failures;
            hitTotal = cacheHits;
            missTotal = cacheMisses;
            rejectedTotal = rejected;
            promptTotal = promptTokens;
            completionTotal = completionTokens;
            reasonKeys = new String[failuresByReason.size()];
            reasonValues = new long[reasonKeys.length];
            int reasonIndex = 0;
            for (Map.Entry<String, Long> entry : failuresByReason.entrySet()) {
                reasonKeys[reasonIndex] = entry.getKey();
                reasonValues[reasonIndex] = entry.getValue().longValue();
                reasonIndex++;
            }
            providerIds = new String[providers.size()];
            providerFields = new long[providers.size() * PROVIDER_FIELDS];
            captureProviderFields(providerIds, providerFields);
            sampleCount = latencyCount;
        }
        Map<String, Long> reasons = new LinkedHashMap<String, Long>(reasonKeys.length * 2 + 1);
        for (int index = 0; index < reasonKeys.length; index++) {
            reasons.put(reasonKeys[index], Long.valueOf(reasonValues[index]));
        }
        List<ProviderLine> lines = providerLines(providerIds, providerFields);
        // Copied outside the monitor: the ring is a fixed-length array and the count only grows, so a
        // sample recorded during the copy is simply one of the newer measurements. Copying it under
        // the monitor put a four kilobyte allocation on the same lock every worker settles under.
        long[] samples = Arrays.copyOf(latencySamples, Math.min(sampleCount, LATENCY_SAMPLE_CAPACITY));
        Snapshot built = new Snapshot(requestTotal, successTotal, failureTotal, hitTotal, missTotal,
                rejectedTotal, promptTotal, completionTotal, reasons, lines, samples);
        synchronized (lock) {
            // Two threads may build the same view; the newer revision wins so a slow builder cannot
            // replace a fresher view with an older one.
            if (cachedRevision < builtRevision) {
                cachedSnapshot = built;
                cachedRevision = builtRevision;
            }
        }
        return built;
    }

    /**
     * Copies the per-provider counters into two parallel arrays; must be called with {@link #lock}
     * held.
     *
     * <p>{@code ids} holds {@code providers.size()} references and {@code fields} seven longs per
     * provider, in {@link ProviderLine} order, so the immutable lines can be built after the monitor
     * has been released.
     */
    private void captureProviderFields(String[] ids, long[] fields) {
        int index = 0;
        for (Map.Entry<String, ProviderCounters> entry : providers.entrySet()) {
            ProviderCounters counters = entry.getValue();
            ids[index] = entry.getKey();
            int base = index * PROVIDER_FIELDS;
            fields[base] = counters.requests;
            fields[base + 1] = counters.successes;
            fields[base + 2] = counters.failures;
            fields[base + 3] = counters.latencySum;
            fields[base + 4] = counters.latencyCount;
            fields[base + 5] = counters.promptTokens;
            fields[base + 6] = counters.completionTokens;
            index++;
        }
    }

    /** One immutable line per provider, built from the arrays {@link #captureProviderFields} filled. */
    private static List<ProviderLine> providerLines(String[] ids, long[] fields) {
        List<ProviderLine> lines = new ArrayList<ProviderLine>(ids.length);
        for (int index = 0; index < ids.length; index++) {
            int base = index * PROVIDER_FIELDS;
            lines.add(new ProviderLine(ids[index], fields[base], fields[base + 1], fields[base + 2],
                    fields[base + 3], fields[base + 4], fields[base + 5], fields[base + 6]));
        }
        return lines;
    }

    /**
     * The statistics as localizable lines, for a settings or diagnostics panel.
     *
     * @param translator platform language-key resolver
     * @return display lines, never {@code null}
     */
    public List<String> localizedLines(UiTranslator translator) {
        UiTranslator ui = translator == null ? (key, arguments) -> key : translator;
        long buildRevision;
        List<String> cached;
        long cachedVersion;
        synchronized (lock) {
            buildRevision = revision;
            cached = cachedLines;
            cachedVersion = cachedLinesRevision;
        }
        Snapshot snapshot = snapshot();
        String totals = ui.translate("screen.universal_translator.stats.totals",
                Long.valueOf(snapshot.requests()), Long.valueOf(snapshot.successes()),
                Long.valueOf(snapshot.failures()));
        if (cached != null && cachedVersion == buildRevision && !cached.isEmpty()
                && cached.get(0).equals(totals)) {
            // The counters did not move and the first line still resolves to the same text, so the
            // language did not change either: a language switch moves no counter, and rebuilding is
            // only due once the wording actually differs. One rebuilt line is one lookup against the
            // five this cache exists to avoid, and it keeps the reuse correct even on a platform that
            // does not call invalidateLocalizedLines() when its resources reload.
            return cached;
        }
        List<String> lines = new ArrayList<String>(6);
        lines.add(totals);
        lines.add(ui.translate("screen.universal_translator.stats.cache",
                Long.valueOf(snapshot.cacheHits()), Long.valueOf(snapshot.cacheMisses()),
                snapshot.cacheHitRate()));
        lines.add(ui.translate("screen.universal_translator.stats.latency",
                Long.valueOf(snapshot.averageLatencyMillis()),
                Long.valueOf(snapshot.percentileLatencyMillis(95))));
        lines.add(ui.translate("screen.universal_translator.stats.tokens",
                Long.valueOf(snapshot.promptTokens()), Long.valueOf(snapshot.completionTokens())));
        if (!snapshot.failuresByReason().isEmpty()) {
            StringBuilder reasons = new StringBuilder();
            for (Map.Entry<String, Long> entry : snapshot.failuresByReason().entrySet()) {
                if (reasons.length() > 0) {
                    reasons.append("  ");
                }
                reasons.append(ui.translate(
                        "screen.universal_translator.stats.reason." + entry.getKey()))
                        .append('=').append(entry.getValue());
            }
            lines.add(ui.translate("screen.universal_translator.stats.reasons", reasons.toString()));
        }
        lines = Collections.unmodifiableList(lines);
        synchronized (lock) {
            if (buildRevision >= cachedLinesRevision) {
                cachedLines = lines;
                cachedLinesRevision = buildRevision;
            }
        }
        return lines;
    }

    /**
     * Drops the cached display lines.
     *
     * <p>The lines are cached by counter revision, and the text they carry also depends on the game
     * language, which moves no counter. A platform calls this when its resources reload after a
     * language change so the diagnostics page does not keep serving the previous language.
     */
    public void invalidateLocalizedLines() {
        synchronized (lock) {
            cachedLines = null;
            cachedLinesRevision = -1L;
        }
    }

    /** The statistics as plain lines, for the exported diagnostics report. */
    public List<String> plainLines() {
        Snapshot snapshot = snapshot();
        List<String> lines = new ArrayList<String>(5);
        lines.add("Statistics: requests=" + snapshot.requests() + " success=" + snapshot.successes()
                + " failure=" + snapshot.failures() + " rejected=" + snapshot.rejected());
        lines.add("Statistics cache: hit=" + snapshot.cacheHits() + " miss=" + snapshot.cacheMisses()
                + " hit-rate=" + snapshot.cacheHitRate() + "%");
        lines.add("Statistics latency: average=" + snapshot.averageLatencyMillis() + "ms p50="
                + snapshot.percentileLatencyMillis(50) + "ms p95="
                + snapshot.percentileLatencyMillis(95) + "ms");
        lines.add("Statistics tokens: prompt=" + snapshot.promptTokens() + " completion="
                + snapshot.completionTokens());
        if (!snapshot.failuresByReason().isEmpty()) {
            lines.add("Statistics failures: " + snapshot.failuresByReason());
        }
        for (ProviderLine provider : snapshot.providers()) {
            lines.add("Statistics provider " + safeProviderId(provider.id()) + ": requests="
                    + provider.requests() + " success=" + provider.successes() + " failure="
                    + provider.failures() + " average=" + provider.averageLatencyMillis() + "ms");
        }
        return lines;
    }

    /**
     * A provider id with any endpoint path and query removed.
     *
     * <p>The exported report promises that endpoints are excluded, and a provider id such as
     * {@code libretranslate:https://host/translate} carries one. The host is kept because it is what
     * makes a per-provider line useful; everything after it is not.
     *
     * @param providerId provider id, possibly {@code null}
     * @return the id, shortened when it embeds a URL
     */
    static String safeProviderId(String providerId) {
        String value = providerId == null ? "unknown" : providerId.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            int path = value.indexOf('/', scheme + 3);
            if (path >= 0) {
                value = value.substring(0, path) + "/...";
            }
        }
        // Cache keys end with the model digest and an optional prompt signature. Neither helps a
        // support request, and the digest is still a fingerprint of what the player was sending.
        int digest = value.indexOf('@');
        int signature = value.indexOf('#');
        int cut = digest < 0 ? signature : (signature < 0 ? digest : Math.min(digest, signature));
        return cut < 0 ? value : value.substring(0, cut);
    }

    /**
     * Classifies a failure for the per-reason counters.
     *
     * @param failure throwable a provider or validator raised, possibly {@code null}
     * @return one of the {@code REASON_*} keys
     */
    public static String reasonOf(Throwable failure) {
        Throwable cause = failure;
        // Bounded walk: a self-referential cause chain must not spin here.
        for (int depth = 0; cause != null && depth < 16; depth++) {
            // HttpStatusException is an IOException, so it has to be recognised first.
            if (cause instanceof HttpStatusException) {
                int status = ((HttpStatusException) cause).getStatusCode();
                if (status == 401 || status == 403) {
                    return REASON_AUTH;
                }
                if (status == 429) {
                    return REASON_RATE_LIMIT;
                }
                return status >= 500 ? REASON_SERVER : REASON_CLIENT;
            }
            if (cause instanceof SocketTimeoutException) {
                return REASON_TIMEOUT;
            }
            if (cause instanceof IOException) {
                return REASON_NETWORK;
            }
            if (cause instanceof IllegalArgumentException) {
                return REASON_INVALID_OUTPUT;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return REASON_OTHER;
    }

    /** Whether {@code reason} is one of the buckets {@link #reasonOf} can produce. */
    private static boolean isKnownReason(String reason) {
        return REASON_AUTH.equals(reason)
                || REASON_RATE_LIMIT.equals(reason)
                || REASON_SERVER.equals(reason)
                || REASON_CLIENT.equals(reason)
                || REASON_TIMEOUT.equals(reason)
                || REASON_NETWORK.equals(reason)
                || REASON_INVALID_OUTPUT.equals(reason)
                || REASON_OTHER.equals(reason);
    }

    private ProviderCounters counters(String providerId) {
        String key = providerId == null || providerId.trim().isEmpty() ? "unknown" : providerId.trim();
        ProviderCounters counters = providers.get(key);
        if (counters != null) {
            return counters;
        }
        if (providers.size() >= MAXIMUM_STORED_PROVIDERS) {
            // Runtime ids carry the endpoint and the model name, so every settings change invents a
            // new key. Overflow shares one bucket instead of growing the map and the statistics file
            // without bound; the same bound already guards the keys read back from that file.
            key = PROVIDER_OVERFLOW;
            counters = providers.get(key);
            if (counters != null) {
                return counters;
            }
        }
        counters = new ProviderCounters();
        providers.put(key, counters);
        return counters;
    }

    private void addLatencySample(long latencyMillis) {
        latencySamples[latencyCursor] = Math.max(0L, latencyMillis);
        latencyCursor = (latencyCursor + 1) % LATENCY_SAMPLE_CAPACITY;
        if (latencyCount < LATENCY_SAMPLE_CAPACITY) {
            latencyCount++;
        }
    }

    private void startFlusher() {
        synchronized (lock) {
            if (flusherStarted) {
                return;
            }
            flusherStarted = true;
        }
        Thread flusher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                flush();
            }
        }, "universal-translator-stats");
        flusher.setDaemon(true);
        flusher.start();
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(TranslationStats.this::flush,
                    "universal-translator-stats-final"));
        } catch (IllegalStateException | SecurityException shuttingDown) {
            // Already shutting down: the periodic flush has done its part.
        }
    }

    private void load(Path source) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | RuntimeException unreadable) {
            return;
        }
        synchronized (lock) {
            requests = longValue(properties, "requests");
            successes = longValue(properties, "successes");
            failures = longValue(properties, "failures");
            cacheHits = longValue(properties, "cache-hits");
            cacheMisses = longValue(properties, "cache-misses");
            rejected = longValue(properties, "rejected");
            promptTokens = longValue(properties, "prompt-tokens");
            completionTokens = longValue(properties, "completion-tokens");
            for (String key : properties.stringPropertyNames()) {
                long value = longValue(properties, key);
                if (value <= 0L) {
                    continue;
                }
                if (key.startsWith(FAILURE_PREFIX) && key.length() > FAILURE_PREFIX.length()) {
                    String reason = key.substring(FAILURE_PREFIX.length());
                    // Only the buckets this class can produce are adopted. Unlike the provider table
                    // this map had no bound at all, so a corrupted file could grow it and then have
                    // the whole thing written back on every flush.
                    if (isKnownReason(reason)) {
                        failuresByReason.put(reason, Long.valueOf(value));
                    }
                    continue;
                }
                if (key.startsWith(PROVIDER_PREFIX) && key.length() > PROVIDER_PREFIX.length()
                        && providers.size() < MAXIMUM_STORED_PROVIDERS) {
                    String remainder = key.substring(PROVIDER_PREFIX.length());
                    int separator = remainder.lastIndexOf('.');
                    if (separator <= 0) {
                        continue;
                    }
                    String id = remainder.substring(0, separator);
                    ProviderCounters counters = counters(id);
                    String field = remainder.substring(separator + 1);
                    if ("requests".equals(field)) {
                        counters.requests = value;
                    } else if ("successes".equals(field)) {
                        counters.successes = value;
                    } else if ("failures".equals(field)) {
                        counters.failures = value;
                    } else if ("latency-sum".equals(field)) {
                        counters.latencySum = value;
                    } else if ("latency-count".equals(field)) {
                        counters.latencyCount = value;
                    } else if ("prompt-tokens".equals(field)) {
                        counters.promptTokens = value;
                    } else if ("completion-tokens".equals(field)) {
                        counters.completionTokens = value;
                    }
                }
            }
            revision++;
        }
    }

    private Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("requests", Long.toString(requests));
        properties.setProperty("successes", Long.toString(successes));
        properties.setProperty("failures", Long.toString(failures));
        properties.setProperty("cache-hits", Long.toString(cacheHits));
        properties.setProperty("cache-misses", Long.toString(cacheMisses));
        properties.setProperty("rejected", Long.toString(rejected));
        properties.setProperty("prompt-tokens", Long.toString(promptTokens));
        properties.setProperty("completion-tokens", Long.toString(completionTokens));
        for (Map.Entry<String, Long> entry : failuresByReason.entrySet()) {
            properties.setProperty(FAILURE_PREFIX + entry.getKey(), entry.getValue().toString());
        }
        for (Map.Entry<String, ProviderCounters> entry : providers.entrySet()) {
            String prefix = PROVIDER_PREFIX + entry.getKey() + ".";
            ProviderCounters counters = entry.getValue();
            properties.setProperty(prefix + "requests", Long.toString(counters.requests));
            properties.setProperty(prefix + "successes", Long.toString(counters.successes));
            properties.setProperty(prefix + "failures", Long.toString(counters.failures));
            properties.setProperty(prefix + "latency-sum", Long.toString(counters.latencySum));
            properties.setProperty(prefix + "latency-count", Long.toString(counters.latencyCount));
            properties.setProperty(prefix + "prompt-tokens", Long.toString(counters.promptTokens));
            properties.setProperty(prefix + "completion-tokens",
                    Long.toString(counters.completionTokens));
        }
        return properties;
    }

    private static long longValue(Properties properties, String key) {
        try {
            long parsed = Long.parseLong(properties.getProperty(key, "0").trim());
            return parsed < 0L ? 0L : parsed;
        } catch (NumberFormatException | NullPointerException ignored) {
            return 0L;
        }
    }

    private static long number(Object first, Object second) {
        long value = asLong(first);
        return value > 0L ? value : asLong(second);
    }

    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /** Mutable per-provider counters; only touched under {@link #lock}. */
    private static final class ProviderCounters {
        private long requests;
        private long successes;
        private long failures;
        private long latencySum;
        private long latencyCount;
        private long promptTokens;
        private long completionTokens;
    }

    /** One provider's counters, as read by a caller. */
    public static final class ProviderLine {
        private final String id;
        private final long requests;
        private final long successes;
        private final long failures;
        private final long latencySum;
        private final long latencyCount;
        private final long promptTokens;
        private final long completionTokens;

        private ProviderLine(
                String id,
                long requests,
                long successes,
                long failures,
                long latencySum,
                long latencyCount,
                long promptTokens,
                long completionTokens
        ) {
            this.id = id;
            this.requests = requests;
            this.successes = successes;
            this.failures = failures;
            this.latencySum = latencySum;
            this.latencyCount = latencyCount;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        public String id() {
            return id;
        }

        public long requests() {
            return requests;
        }

        public long successes() {
            return successes;
        }

        public long failures() {
            return failures;
        }

        public long promptTokens() {
            return promptTokens;
        }

        public long completionTokens() {
            return completionTokens;
        }

        public long averageLatencyMillis() {
            return latencyCount <= 0L ? 0L : latencySum / latencyCount;
        }
    }

    /** An immutable view of every counter. */
    public static final class Snapshot {
        private final long requests;
        private final long successes;
        private final long failures;
        private final long cacheHits;
        private final long cacheMisses;
        private final long rejected;
        private final long promptTokens;
        private final long completionTokens;
        private final Map<String, Long> failuresByReason;
        private final List<ProviderLine> providers;
        /**
         * The retained samples, owned by this view. A primitive array rather than a list of boxed
         * longs: this is rebuilt whenever a counter moves, and one {@code Long} per sample was the
         * bulk of that cost.
         */
        private final long[] latencySamples;
        /** Sum of {@link #latencySamples}, computed once so a panel can ask for the average freely. */
        private final long latencyTotal;
        /**
         * Ascending copy of the latency samples, built on first use and shared by every percentile.
         * Volatile so the render thread never publishes a half-filled array to a later reader.
         */
        private volatile long[] sortedSamples;

        private Snapshot(
                long requests,
                long successes,
                long failures,
                long cacheHits,
                long cacheMisses,
                long rejected,
                long promptTokens,
                long completionTokens,
                Map<String, Long> failuresByReason,
                List<ProviderLine> providers,
                long[] latencySamples
        ) {
            this.requests = requests;
            this.successes = successes;
            this.failures = failures;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.rejected = rejected;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.failuresByReason = Collections.unmodifiableMap(failuresByReason);
            this.providers = Collections.unmodifiableList(providers);
            this.latencySamples = latencySamples;
            long total = 0L;
            for (long sample : latencySamples) {
                total += sample;
            }
            this.latencyTotal = total;
        }

        public long requests() {
            return requests;
        }

        public long successes() {
            return successes;
        }

        public long failures() {
            return failures;
        }

        public long cacheHits() {
            return cacheHits;
        }

        public long cacheMisses() {
            return cacheMisses;
        }

        /**
         * Requests whose translation was discarded because the assembled text failed the display
         * check. Together with {@link #successes()} and {@link #failures()} this accounts for every
         * request, which is what the diagnostics screen promises.
         */
        public long rejected() {
            return rejected;
        }

        public long promptTokens() {
            return promptTokens;
        }

        public long completionTokens() {
            return completionTokens;
        }

        public Map<String, Long> failuresByReason() {
            return failuresByReason;
        }

        public List<ProviderLine> providers() {
            return providers;
        }

        /** Share of lookups answered from the cache, as a whole percentage. */
        public long cacheHitRate() {
            long total = cacheHits + cacheMisses;
            return total <= 0L ? 0L : Math.round(cacheHits * 100.0d / total);
        }

        /** Average request latency over the retained samples, or {@code 0} when none were kept. */
        public long averageLatencyMillis() {
            int count = latencySamples.length;
            return count <= 0 ? 0L : latencyTotal / count;
        }

        /**
         * Latency at a percentile over the retained samples.
         *
         * <p>The samples are the most recent {@link #LATENCY_SAMPLE_CAPACITY} requests rather than
         * the whole session, because keeping every latency would grow without bound; the value is
         * therefore a moving picture of recent behaviour.
         *
         * @param percentile 0–100; values outside that range are clamped
         * @return latency in milliseconds
         */
        public long percentileLatencyMillis(int percentile) {
            long[] sorted = sortedSamples();
            if (sorted.length == 0) {
                return 0L;
            }
            int bounded = Math.max(0, Math.min(100, percentile));
            // Integer arithmetic: the floating point form landed on the neighbouring index for
            // combinations whose product is an exact integer but not representable in binary.
            int position = (bounded * sorted.length + 99) / 100 - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, position))];
        }

        /**
         * The samples in ascending order, sorted on first use.
         *
         * <p>One snapshot answers several percentiles -- the exported report asks for p50 and p95
         * of the same view -- and the diagnostics panel redraws the same view every frame, so the
         * sort must not run per call. Two threads may race to build it; both produce the same array
         * and the field is only published once it is complete.
         */
        private long[] sortedSamples() {
            long[] local = sortedSamples;
            if (local == null) {
                local = latencySamples.clone();
                Arrays.sort(local);
                sortedSamples = local;
            }
            return local;
        }

        /** The retained latency sample count, for a panel that wants to say how it was measured. */
        public int latencySampleCount() {
            return latencySamples.length;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "requests=%d success=%d failure=%d cache=%d/%d tokens=%d+%d",
                    requests, successes, failures, cacheHits, cacheMisses, promptTokens,
                    completionTokens);
        }
    }
}
