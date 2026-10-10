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
    private static final String PROVIDER_PREFIX = "provider.";
    private static final String FAILURE_PREFIX = "failure.";

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
            if (file != null) {
                return;
            }
            file = statisticsFile;
        }
        load(statisticsFile);
        startFlusher();
    }

    /** Records that a translation was answered from the cache, without touching the provider. */
    public void recordCacheHit() {
        synchronized (lock) {
            cacheHits++;
        }
    }

    /** Records that a translation had to be requested from the provider. */
    public void recordCacheMiss() {
        synchronized (lock) {
            cacheMisses++;
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
     */
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
        if (!(root instanceof Map)) {
            return;
        }
        Map<?, ?> object = (Map<?, ?>) root;
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
        if (prompt <= 0L && completion <= 0L) {
            return;
        }
        synchronized (lock) {
            promptTokens += prompt;
            completionTokens += completion;
            ProviderCounters counters = counters(providerId);
            counters.promptTokens += prompt;
            counters.completionTokens += completion;
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
            promptTokens = 0L;
            completionTokens = 0L;
        }
        flush();
    }

    /** Writes the counters out. Never throws: statistics must not be able to break a translation. */
    public void flush() {
        Path target = file;
        if (target == null) {
            return;
        }
        Properties properties;
        synchronized (lock) {
            properties = toProperties();
        }
        try {
            Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "MC Auto Translation Tool - translation statistics");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            // A read-only or full configuration directory is not a translation failure.
        }
    }

    /** An immutable view of the counters, safe to hand to a render thread. */
    public Snapshot snapshot() {
        synchronized (lock) {
            List<Long> samples = new ArrayList<Long>(latencyCount);
            for (int index = 0; index < latencyCount; index++) {
                samples.add(Long.valueOf(latencySamples[index]));
            }
            List<ProviderLine> lines = new ArrayList<ProviderLine>(providers.size());
            for (Map.Entry<String, ProviderCounters> entry : providers.entrySet()) {
                ProviderCounters counters = entry.getValue();
                lines.add(new ProviderLine(entry.getKey(), counters.requests, counters.successes,
                        counters.failures, counters.latencySum, counters.latencyCount,
                        counters.promptTokens, counters.completionTokens));
            }
            return new Snapshot(requests, successes, failures, cacheHits, cacheMisses,
                    promptTokens, completionTokens,
                    new LinkedHashMap<String, Long>(failuresByReason), lines, samples);
        }
    }

    /**
     * The statistics as localizable lines, for a settings or diagnostics panel.
     *
     * @param translator platform language-key resolver
     * @return display lines, never {@code null}
     */
    public List<String> localizedLines(UiTranslator translator) {
        UiTranslator ui = translator == null ? (key, arguments) -> key : translator;
        Snapshot snapshot = snapshot();
        List<String> lines = new ArrayList<String>(6);
        lines.add(ui.translate("screen.universal_translator.stats.totals",
                Long.valueOf(snapshot.requests()), Long.valueOf(snapshot.successes()),
                Long.valueOf(snapshot.failures())));
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
        return Collections.unmodifiableList(lines);
    }

    /** The statistics as plain lines, for the exported diagnostics report. */
    public List<String> plainLines() {
        Snapshot snapshot = snapshot();
        List<String> lines = new ArrayList<String>(5);
        lines.add("Statistics: requests=" + snapshot.requests() + " success=" + snapshot.successes()
                + " failure=" + snapshot.failures());
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
        if (scheme < 0) {
            return value;
        }
        int path = value.indexOf('/', scheme + 3);
        return path < 0 ? value : value.substring(0, path) + "/...";
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

    private ProviderCounters counters(String providerId) {
        String key = providerId == null || providerId.trim().isEmpty() ? "unknown" : providerId.trim();
        ProviderCounters counters = providers.get(key);
        if (counters == null) {
            counters = new ProviderCounters();
            providers.put(key, counters);
        }
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
            promptTokens = longValue(properties, "prompt-tokens");
            completionTokens = longValue(properties, "completion-tokens");
            for (String key : properties.stringPropertyNames()) {
                long value = longValue(properties, key);
                if (value <= 0L) {
                    continue;
                }
                if (key.startsWith(FAILURE_PREFIX) && key.length() > FAILURE_PREFIX.length()) {
                    failuresByReason.put(key.substring(FAILURE_PREFIX.length()), Long.valueOf(value));
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
        }
    }

    private Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("requests", Long.toString(requests));
        properties.setProperty("successes", Long.toString(successes));
        properties.setProperty("failures", Long.toString(failures));
        properties.setProperty("cache-hits", Long.toString(cacheHits));
        properties.setProperty("cache-misses", Long.toString(cacheMisses));
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
        private final long promptTokens;
        private final long completionTokens;
        private final Map<String, Long> failuresByReason;
        private final List<ProviderLine> providers;
        private final List<Long> latencySamples;

        private Snapshot(
                long requests,
                long successes,
                long failures,
                long cacheHits,
                long cacheMisses,
                long promptTokens,
                long completionTokens,
                Map<String, Long> failuresByReason,
                List<ProviderLine> providers,
                List<Long> latencySamples
        ) {
            this.requests = requests;
            this.successes = successes;
            this.failures = failures;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.failuresByReason = Collections.unmodifiableMap(failuresByReason);
            this.providers = Collections.unmodifiableList(providers);
            this.latencySamples = Collections.unmodifiableList(latencySamples);
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
            if (latencySamples.isEmpty()) {
                return 0L;
            }
            long total = 0L;
            for (Long sample : latencySamples) {
                total += sample.longValue();
            }
            return total / latencySamples.size();
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
            if (latencySamples.isEmpty()) {
                return 0L;
            }
            int bounded = Math.max(0, Math.min(100, percentile));
            long[] sorted = new long[latencySamples.size()];
            for (int index = 0; index < sorted.length; index++) {
                sorted[index] = latencySamples.get(index).longValue();
            }
            Arrays.sort(sorted);
            int position = (int) Math.ceil(bounded / 100.0d * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, position))];
        }

        /** The retained latency sample count, for a panel that wants to say how it was measured. */
        public int latencySampleCount() {
            return latencySamples.size();
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
