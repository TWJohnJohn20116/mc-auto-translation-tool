package org.universaltranslator.core;

/**
 * Receives the translation of one request while the provider is still generating it.
 *
 * <p>Every callback carries the text produced <em>so far</em> rather than the increment since the
 * previous call. The display only ever replaces its preview with the newest value, so a caller that
 * was slow, or that joined a request another thread had already started, still ends up showing the
 * complete text instead of a fragment.
 *
 * <p>Implementations must be cheap and must never throw: they run on the translation worker while
 * the response is being read, and an exception there would abort a request that is otherwise fine.
 */
public interface TranslationStreamListener {
    /**
     * Called once per generated fragment, always with the accumulated translation.
     *
     * @param partialText translation of the current request as it stands, never {@code null} and
     *                    never empty
     */
    void onPartialText(String partialText);
}
