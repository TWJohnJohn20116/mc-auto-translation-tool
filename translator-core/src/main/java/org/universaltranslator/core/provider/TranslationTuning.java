package org.universaltranslator.core.provider;

/**
 * How many translations run at once, how many texts one request may carry, how long a request waits
 * for siblings to batch with, and the smallest spacing between two requests to one endpoint.
 *
 * <p>These are throughput settings, not prompt settings: none of them changes the system prompt or
 * the text a request carries. They are therefore deliberately kept out of
 * {@link org.universaltranslator.core.TranslationPrompt.Settings#signature()} and the translation
 * cache key is unaffected by them, so changing one can never invalidate a cached translation or
 * make two identical requests disagree.
 *
 * <p>Every value is clamped rather than rejected. A configuration file is edited by hand as often
 * as by a settings screen, and a typo there must not stop translation from working; an unusable
 * value falls back to the documented default and an out-of-range one is pulled to the nearest end
 * of its range. {@code OnlineProviderConfig.effectiveSettingsLines(...)} reports both the effective
 * value and the value it came from, so a clamp is visible in the diagnostics bundle.
 */
public final class TranslationTuning {
    /** Concurrent translation threads when the configuration says nothing. */
    public static final int DEFAULT_WORKERS = 4;
    public static final int MINIMUM_WORKERS = 1;
    public static final int MAXIMUM_WORKERS = 16;

    /** Texts carried by one request when the configuration says nothing. */
    public static final int DEFAULT_BATCH_SIZE = 8;
    public static final int MINIMUM_BATCH_SIZE = 1;
    public static final int MAXIMUM_BATCH_SIZE = 16;

    /** Batch collection window in milliseconds when the configuration says nothing. */
    public static final int DEFAULT_BATCH_WINDOW_MILLIS = 30;
    public static final int MINIMUM_BATCH_WINDOW_MILLIS = 0;
    public static final int MAXIMUM_BATCH_WINDOW_MILLIS = 500;

    /**
     * Attempts one request may make, and the smallest spacing between two requests to the same
     * endpoint.
     *
     * <p>Both ranges are enforced here only. They used to be written out twice — once in
     * {@code OnlineProviderConfig.integer(...)} and once inside
     * {@link ResilientTranslationProvider}'s constructor — so changing one of them made the other
     * clamp the value silently.
     */
    public static final int DEFAULT_ATTEMPTS = 3;
    public static final int MINIMUM_ATTEMPTS = 1;
    public static final int MAXIMUM_ATTEMPTS = 5;
    public static final int DEFAULT_REQUEST_INTERVAL_MILLIS = 60;
    public static final int MINIMUM_REQUEST_INTERVAL_MILLIS = 0;
    public static final int MAXIMUM_REQUEST_INTERVAL_MILLIS = 60000;

    /** The historical hardcoded behaviour, used by every constructor that is not told otherwise. */
    public static final TranslationTuning DEFAULTS = new TranslationTuning(
            DEFAULT_WORKERS, DEFAULT_BATCH_SIZE, DEFAULT_BATCH_WINDOW_MILLIS);

    private final int workers;
    private final int batchSize;
    private final int batchWindowMillis;
    private final int requestIntervalMillis;

    public TranslationTuning(int workers, int batchSize, int batchWindowMillis) {
        this(workers, batchSize, batchWindowMillis, DEFAULT_REQUEST_INTERVAL_MILLIS);
    }

    /**
     * @param requestIntervalMillis smallest spacing between two requests to one endpoint, in
     *                              milliseconds; 0 sends as fast as the worker pool allows
     */
    public TranslationTuning(int workers, int batchSize, int batchWindowMillis,
            int requestIntervalMillis) {
        this.workers = clamp(workers, MINIMUM_WORKERS, MAXIMUM_WORKERS);
        this.batchSize = clamp(batchSize, MINIMUM_BATCH_SIZE, MAXIMUM_BATCH_SIZE);
        this.batchWindowMillis = clamp(batchWindowMillis,
                MINIMUM_BATCH_WINDOW_MILLIS, MAXIMUM_BATCH_WINDOW_MILLIS);
        this.requestIntervalMillis = clamp(requestIntervalMillis,
                MINIMUM_REQUEST_INTERVAL_MILLIS, MAXIMUM_REQUEST_INTERVAL_MILLIS);
    }

    /**
     * Concurrent translation threads, as configured.
     *
     * <p>No provider reads this. The concurrency of provider requests is bounded by the platform's
     * thread pool, not by the provider: {@code translation-workers} →
     * {@code FabricTranslationRuntimeBase.threads()} → {@code TranslationCoordinator}'s
     * {@code ThreadPoolExecutor}. The provider only ever consults {@link #batchSize()} and
     * {@link #batchWindowMillis()}, so this value is reported twice and changing it here changes
     * nothing at runtime — which is why {@link #toString()} no longer prints it and why anyone
     * looking for "why does raising the worker count change nothing" should start at the executor.
     *
     * @deprecated kept so existing callers and the configuration round trip still compile; read the
     *     platform's worker count instead.
     */
    @Deprecated
    public int workers() {
        return workers;
    }

    /** Largest number of texts one request may carry. */
    public int batchSize() {
        return batchSize;
    }

    /**
     * How many groups of one job may be in request at the same time.
     *
     * <p>The batch gatherer used to allow exactly one leader per job, and a session's texts share
     * one job (one target language, one text kind), so every worker waited behind the same leader
     * and the worker count bought no parallelism at all. The worker count is the natural cap: more
     * groups in flight than threads to send them cannot happen.
     *
     * @return the worker count, already inside its range
     */
    public int maximumConcurrentBatches() {
        return clamp(workers, MINIMUM_WORKERS, MAXIMUM_WORKERS);
    }

    /** How long a request waits for siblings, in milliseconds; 0 does not wait at all. */
    public int batchWindowMillis() {
        return batchWindowMillis;
    }

    /**
     * Smallest spacing between two requests to one endpoint, in milliseconds.
     *
     * <p>{@link ResilientTranslationProvider} owns the gate for one translation call, but a batch
     * sends more than one request from inside a single call: one whole-group retry, and one repair
     * request per line the reply did not answer. Those requests bypassed that gate entirely, so
     * {@code api-min-request-interval-ms} meant nothing on the most expensive request there is. The
     * value travels here so the batch layer can pace them with the same number the outer gate uses.
     */
    public int requestIntervalMillis() {
        return requestIntervalMillis;
    }

    /**
     * Whether one request may carry more than one text.
     *
     * <p>{@code batch-size = 1} turns batching off completely: the provider then translates every
     * text with a request of its own and never parks a worker in the collection window, which is
     * what an endpoint with a tiny rate limit needs.
     */
    public boolean batching() {
        return batchSize > 1;
    }

    /** Pulls {@code value} into {@code minimum..maximum}. */
    public static int clamp(int value, int minimum, int maximum) {
        if (value < minimum) {
            return minimum;
        }
        return value > maximum ? maximum : value;
    }

    /**
     * Parses a configured value, falling back to {@code fallback} when it is not a number and
     * clamping it when it is outside {@code minimum..maximum}.
     */
    public static int clamp(String raw, int fallback, int minimum, int maximum) {
        String trimmed = raw == null ? "" : raw.trim();
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException notANumber) {
            parsed = fallback;
        }
        return clamp(parsed, minimum, maximum);
    }

    /** The settings this object actually decides; the worker count is not one of them. */
    @Override
    public String toString() {
        return "batchSize=" + batchSize + ", batchWindowMillis=" + batchWindowMillis
                + ", requestIntervalMillis=" + requestIntervalMillis;
    }
}
