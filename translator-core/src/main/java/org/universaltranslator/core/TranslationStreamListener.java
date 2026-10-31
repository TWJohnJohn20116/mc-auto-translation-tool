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
     * Called while the answer is being generated, always with the accumulated translation.
     *
     * <p>It is not called for every fragment: the providers publish at most once per
     * {@code PARTIAL_TEXT_STEP_CHARS} characters, so an answer shorter than that step arrives in one
     * closing callback and nothing before it. <strong>The last call always carries the complete
     * translation</strong>, which is what a preview that only ever replaces its text can rely on.
     *
     * @param partialText translation of the current request as it stands, never {@code null} and
     *                    never empty
     */
    void onPartialText(String partialText);
}
