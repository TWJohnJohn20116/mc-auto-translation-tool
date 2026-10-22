package org.universaltranslator.core.provider;

/**
 * The context window of the loopback llama.cpp server this mod starts.
 *
 * <p>Two classes have to agree on this number: {@link LlamaCppOfflineProvider} passes it as
 * {@code --ctx-size}, and {@link OpenAiChatTranslationProvider} caps the completion budget to what
 * the window leaves free. They used to hold their own copy, so changing the server's window would
 * silently let the completion budget overflow it.
 */
final class LoopbackContext {
    /** Tokens the loopback server keeps in its single shared context window. */
    static final int TOKENS = 1024;

    private LoopbackContext() {
    }
}
