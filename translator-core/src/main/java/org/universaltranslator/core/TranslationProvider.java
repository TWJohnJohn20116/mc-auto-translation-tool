package org.universaltranslator.core;

/** A user-selected local or remote translation engine. Implementations must be thread-safe. */
public interface TranslationProvider {
    String id();

    String translate(TranslationRequest request) throws Exception;
}
