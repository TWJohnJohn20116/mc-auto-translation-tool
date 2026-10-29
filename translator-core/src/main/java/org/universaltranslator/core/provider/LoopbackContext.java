package org.universaltranslator.core.provider;

/**
 * The context window of the loopback llama.cpp server this mod starts.
 *
 * <p>Three places consume this number and have to agree on it:
 *
 * <ul>
 *   <li>{@link LlamaCppOfflineProvider} passes it as {@code --ctx-size} and asks
 *       {@link ChatBudget#remainingContext} whether a text fits;
 *   <li>{@link OpenAiChatTranslationProvider} caps the completion budget to what the window leaves
 *       free;
 *   <li>{@link ChatBudget} owns the subtraction itself, so the two providers cannot disagree about
 *       where the window runs out.
 * </ul>
 *
 * <p>They used to hold their own copy, so changing the server's window would silently let the
 * completion budget overflow it.
 */
final class LoopbackContext {
    /** Tokens the loopback server keeps in its single shared context window. */
    static final int TOKENS = 1024;

    private LoopbackContext() {
    }
}
