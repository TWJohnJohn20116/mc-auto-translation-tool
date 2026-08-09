package org.universaltranslator.core;

/** Cache abstraction shared by memory-only and privacy-conscious disk stores. */
public interface TranslationStore {
    String get(String key);

    void put(String key, String value);

    void clear();
}
