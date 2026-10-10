package org.universaltranslator.core;

/** A user-selected local or remote translation engine. Implementations must be thread-safe. */
public interface TranslationProvider {
    String id();

    String translate(TranslationRequest request) throws Exception;

    /**
     * Translates one request and reports the translation while it is still being generated.
     *
     * <p>The default implementation is the plain path with a single callback at the end, which is
     * the correct behaviour for every engine whose protocol has no incremental form: the offline
     * llama.cpp server, LibreTranslate and the vendor HTTP APIs all answer with one document. A
     * provider that can stream — the OpenAI-compatible chat endpoints — overrides this.
     *
     * <p>Callers must treat the returned value, not the last callback, as the translation: a
     * provider is free to ignore the listener, and the value has been through the same validation
     * the plain path applies.
     *
     * @param request text to translate
     * @param listener receives the accumulated translation as it grows, never {@code null}
     * @return the completed translation
     * @throws Exception when the request fails; a caller that has already shown a partial text must
     *                   discard it and show the failure instead
     */
    default String translateStreaming(TranslationRequest request, TranslationStreamListener listener)
            throws Exception {
        String translated = translate(request);
        if (translated != null) {
            listener.onPartialText(translated);
        }
        return translated;
    }
}
