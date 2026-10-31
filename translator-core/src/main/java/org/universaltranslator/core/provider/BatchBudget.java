package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.HttpStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Posts one batch, halving the completion budget while the endpoint refuses it, and remembers the
 * value that was accepted.
 *
 * <p>A batch of long lines needs more output tokens than a single line, and every endpoint caps what
 * it will accept. Without the retry, a batch that asked for too much failed and the per-line repair
 * turned one batch into one request per line. Without the memory, every batch of the session paid
 * for the same refusal again: an endpoint capped at 8192 answered 400, refused the halved value as
 * well, and sixteen repair requests followed, so a forty-line screen cost dozens of requests where
 * three would have done.
 *
 * <p>This used to live inside {@link OpenAiChatTranslationProvider} only. The two providers that
 * gained a batch path later called {@link ChatBudget#batchCompletionBudget} without it, so the same
 * defect was rebuilt on those paths: one guaranteed 400 plus one request per line, on every batch,
 * for the whole session. One implementation, three callers.
 *
 * <p>Only a budget the endpoint actually accepted is recorded, and only when a refusal forced it
 * down: one refused request — including one refusing the <em>input</em> for being too long — must
 * not narrow every later batch of that size (see {@link ByEntries} for why the size is the key).
 */
final class BatchBudget {
    /** Posts one batch request that asks for the given completion budget. */
    interface Poster {
        /**
         * @param budget completion tokens to ask for
         * @return the response body
         * @throws Exception whatever the request failed with
         */
        String post(int budget) throws Exception;
    }

    /**
     * How many times one batch budget may be halved after the endpoint refused it.
     *
     * <p>Three steps reach the smallest values these endpoints ship with; more would spend a paid
     * request per step on an endpoint that will never accept a larger batch than its own cap, and
     * the per-line repair already costs one request per line.
     */
    static final int MAXIMUM_REDUCTIONS = 3;

    private BatchBudget() {
    }

    /**
     * Posts one batch within the largest budget this endpoint has accepted.
     *
     * @param accepted the largest accepted budget of this provider instance
     * @param budget the value this batch asked for
     * @param minimumTokens smallest value worth sending, the floor of the halving
     * @param poster how one attempt is sent
     * @return the body of the attempt that was accepted
     * @throws Exception the last refusal, or whatever the poster threw
     */
    static String post(Accepted accepted, int budget, int minimumTokens, Poster poster)
            throws Exception {
        int attempt = budget;
        int reductions = 0;
        while (true) {
            try {
                String response = poster.post(attempt);
                if (attempt < budget && attempt < accepted.budget()) {
                    accepted.remember(attempt);
                }
                return response;
            } catch (HttpStatusException rejected) {
                // Only a refusal that names the output-token limit may lower the budget: treating
                // any 400 as one turned "this input is too long" into a permanently smaller batch.
                if (!OpenAiChatTranslationProvider.rejectsCompletionBudget(rejected)
                        || reductions >= MAXIMUM_REDUCTIONS) {
                    throw rejected;
                }
                int reduced = halved(attempt, minimumTokens);
                if (reduced >= attempt) {
                    throw rejected;
                }
                attempt = reduced;
                reductions++;
            }
        }
    }

    /**
     * Half of a refused budget, never below {@code minimumTokens}.
     *
     * @param budget the refused budget
     * @param minimumTokens smallest value worth sending
     * @return half of it
     */
    static int halved(int budget, int minimumTokens) {
        return Math.max(minimumTokens, budget / 2);
    }

    /**
     * How much of a refused batch request its longest text may already account for.
     *
     * <p>A refusal that names the request's own length does not say which part of it overflowed. When
     * one text is already the whole prompt, sending it alone overflows too, and narrowing every later
     * batch for it only doubles the number of requests for the rest of the session. Combining the lines
     * has to be what is responsible before {@link ByBatchSize} lowers anything.
     */
    static final int LENGTH_REFUSAL_RATIO = 2;

    /**
     * Whether a refusal means "this one request carried too many texts".
     *
     * <p>Three conditions, all necessary: the endpoint refused the request itself (a client error that
     * is not the output-budget refusal {@link #post} already handles), its message names the request's
     * length, and the batch prompt is materially larger than its longest member — see
     * {@link #LENGTH_REFUSAL_RATIO}. Without the last test a single long tooltip would shrink every
     * later batch, which is the same one-way narrowing {@link ByEntries}'s comment warns about in the
     * other direction.
     *
     * @param failure what the batch request threw
     * @param requestBody the whole batch request as it was sent
     * @param group the texts that request carried
     * @return whether the number of texts is what has to come down
     */
    static boolean refusedBecauseTooManyEntries(
            Throwable failure,
            String requestBody,
            List<TranslationRequest> group
    ) {
        if (!(failure instanceof HttpStatusException)) {
            return false;
        }
        HttpStatusException status = (HttpStatusException) failure;
        if (status.getStatusCode() != 400
                || OpenAiChatTranslationProvider.rejectsCompletionBudget(status)) {
            return false;
        }
        String message = status.getMessage() == null
                ? "" : status.getMessage().toLowerCase(Locale.ROOT);
        if (!message.contains("context") && !message.contains("prompt")
                && !message.contains("input") && !message.contains("too long")) {
            return false;
        }
        int longestLine = 0;
        for (TranslationRequest request : group) {
            longestLine = Math.max(longestLine, ChatBudget.estimatePromptTokens(request.getText()));
        }
        return ChatBudget.estimatePromptTokens(requestBody) > longestLine * LENGTH_REFUSAL_RATIO;
    }

    /**
     * The largest completion budget one endpoint accepted, lowered only by an actual refusal.
     *
     * <p>Held by the provider instance rather than here, so rebuilding a provider from edited
     * settings starts from its own ceiling again, and two endpoints never see each other's limit.
     */
    static final class Accepted {
        private volatile int budget;

        Accepted(int initialBudget) {
            this.budget = initialBudget;
        }

        /** The largest value this endpoint is known to accept. */
        int budget() {
            return budget;
        }

        /** Records a smaller value a refusal proved to be acceptable. */
        void remember(int value) {
            budget = value;
        }
    }

    /**
     * The largest budget accepted for each batch size, held by one provider instance.
     *
     * <p>One value for the whole provider was wrong in both directions: an endpoint that caps its
     * output at 512 tokens taught it 512 on a two-line batch, and every later sixteen-line batch then
     * asked for 512 as well, was truncated at the completion limit and fell back to sixteen
     * single-line repairs — the exact cost the memory exists to prevent, made deterministic by the
     * memory itself. Keyed by the number of texts, a small batch only narrows batches of its own size.
     *
     * <p>Bounded, because a cache of measurements must not grow for the life of the process: starting
     * over costs one refused request per size.
     */
    static final class ByEntries {
        /** How many distinct batch sizes may hold a remembered budget. */
        private static final int MAXIMUM_SIZES = 32;

        private final Map<Integer, Accepted> byEntries = new ConcurrentHashMap<Integer, Accepted>();
        private final int initialBudget;

        ByEntries(int initialBudget) {
            this.initialBudget = initialBudget;
        }

        /**
         * @param entries number of texts the batch carries
         * @return the memory to learn from and to cap with, created on first use
         */
        Accepted forEntries(int entries) {
            Integer key = Integer.valueOf(entries);
            Accepted existing = byEntries.get(key);
            if (existing != null) {
                return existing;
            }
            if (byEntries.size() >= MAXIMUM_SIZES) {
                byEntries.clear();
            }
            Accepted created = new Accepted(initialBudget);
            Accepted raced = byEntries.putIfAbsent(key, created);
            return raced == null ? created : raced;
        }
    }

    /**
     * The largest number of texts one endpoint has been shown to accept in one request.
     *
     * <p>{@link ByEntries} remembers how many output tokens a batch of a given size may ask for, which
     * cannot answer "this batch was refused because it carried too many texts": nothing else lowered the
     * number of texts, so every later batch was rebuilt at the configured size and refused again — one
     * guaranteed failure plus one repair request per line, on every screen, for the whole session.
     *
     * <p>One-way, like {@link Accepted}: the endpoint does not grow back on its own, and editing the
     * settings builds a provider that starts from the configured size again. It never goes below
     * {@link #MINIMUM_ENTRIES}, because a batch of one is the per-line path, whose meaning is
     * "batch-size=1 was configured".
     */
    static final class ByBatchSize {
        /** Smallest number of texts this memory lowers the batch to. */
        static final int MINIMUM_ENTRIES = 2;

        private volatile int maximumEntries;

        ByBatchSize(int configured) {
            this.maximumEntries = Math.max(MINIMUM_ENTRIES, configured);
        }

        /**
         * Records that a batch carrying {@code entries} texts was too large, halving the limit once.
         *
         * @param entries number of texts the refused batch carried
         * @return the largest number of texts one request may carry from now on
         */
        int rememberTooLarge(int entries) {
            int current = maximumEntries;
            if (entries < current || current <= MINIMUM_ENTRIES) {
                return current;
            }
            maximumEntries = Math.max(MINIMUM_ENTRIES, current / 2);
            return maximumEntries;
        }
    }
}
