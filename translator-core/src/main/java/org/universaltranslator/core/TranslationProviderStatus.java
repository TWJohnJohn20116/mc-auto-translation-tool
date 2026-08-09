package org.universaltranslator.core;

/** Optional human-readable state exposed by providers with background installation or startup work. */
public interface TranslationProviderStatus {
    String status();
}
