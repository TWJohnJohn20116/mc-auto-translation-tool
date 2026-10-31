package org.universaltranslator.core.provider;

/**
 * Token arithmetic shared by every provider that has to size a completion.
 *
 * <p>{@link LoopbackContext} already collapsed the context window into one place because two copies
 * had drifted apart. The same three numbers lived on in separate private copies: the 128-token
 * prompt overhead, the 64-token floor below which an answer is a fragment, and the
 * {@code window - overhead - estimate(text)} subtraction that both
 * {@link LlamaCppOfflineProvider} and {@link OpenAiChatTranslationProvider} performed by hand.
 * Changing one copy (say the overhead to 160) would have let the two disagree, which is exactly the
 * bug the {@link LoopbackContext} comment describes.
 *
 * <p>Nothing here holds state; the numbers are constants and the arithmetic is pure.
 */
final class ChatBudget {
    /** Chat-template and system-prompt tokens any provider charges on top of the text itself. */
    static final int PROMPT_OVERHEAD_TOKENS = 128;
    /**
     * Smallest completion budget worth sending. Below this the model answers a fragment, and a
     * non-empty fragment passes validation and is cached as a finished translation.
     */
    static final int MINIMUM_ANSWER_TOKENS = 64;

    private ChatBudget() {
    }

    /**
     * Conservative prompt-token estimate. ASCII text averages about one token per four characters
     * while non-ASCII text such as Chinese tokenizes near one token per character, so both are
     * rounded up to avoid overflowing the context window.
     *
     * @param text text about to be sent
     * @return estimated prompt tokens, never negative
     */
    static int estimatePromptTokens(String text) {
        if (text == null) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) < 0x80) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        return (ascii + 3) / 4 + nonAscii + 1;
    }

    /**
     * What a shared context window leaves for the answer.
     *
     * @param contextTokens size of the window the server was started with
     * @param text text about to be sent
     * @return remaining tokens, possibly negative when the text does not fit at all
     */
    static int remainingContext(int contextTokens, String text) {
        return contextTokens - PROMPT_OVERHEAD_TOKENS - estimatePromptTokens(text);
    }

    /**
     * Completion budget for one text: twice its length plus a little slack, pulled into
     * {@code minimum..ceiling}.
     *
     * <p>A fixed cap truncated long lines mid-sentence, and the clipped result was still short
     * enough to pass {@link org.universaltranslator.core.TranslationOutputValidator}, so it entered
     * the persistent cache. {@code max_tokens} is an upper bound rather than a reservation, so a
     * generous value costs nothing for a model that stops early.
     *
     * @param text text about to be sent
     * @param ceiling largest value this endpoint accepts
     * @param minimum smallest value worth sending
     * @return completion tokens to ask for
     */
    static int completionBudget(String text, int ceiling, int minimum) {
        int length = text == null ? 0 : text.length();
        int requested = Math.min(length * 2 + 32, ceiling);
        return Math.max(minimum, requested);
    }

    /**
     * Per-entry allowance in a batch completion budget for the reply's JSON key, its quotes and the
     * separator between entries.
     */
    static final int BATCH_REPLY_ENTRY_TOKENS = 12;

    /**
     * Completion budget for one batch reply, in prompt tokens rather than characters.
     *
     * <p>Sizing a batch from the JSON <em>character</em> count of the request overestimated by about
     * four times on ASCII text, so every batch asked for the declared ceiling, an endpoint with a
     * smaller output cap refused it, and the batch path paid for that refusal on every batch of the
     * session. The prompt estimate is the number the context arithmetic already trusts, scaled by
     * three: a CJK answer costs about one token per character while the same meaning in ASCII costs
     * a quarter of that per character, which puts a translation near three times its prompt estimate
     * — under it truncates the reply, and a truncated JSON saves nothing.
     *
     * <p>{@code max_tokens} is an upper bound rather than a reservation, so a generous value costs
     * nothing for a model that stops early.
     *
     * @param requestBody the JSON request that was built
     * @param entries number of texts the reply has to carry
     * @param ceiling largest value the endpoint accepts
     * @param minimum smallest value worth sending
     * @return completion tokens to ask for
     */
    static int batchCompletionBudget(String requestBody, int entries, int ceiling, int minimum) {
        int escaping = requestBody == null ? 0 : Math.max(0, requestBody.length() / 16);
        int requested = estimatePromptTokens(requestBody) * 3
                + BATCH_REPLY_ENTRY_TOKENS * Math.max(1, entries) + escaping + 32;
        return Math.max(minimum, Math.min(requested, ceiling));
    }
}
