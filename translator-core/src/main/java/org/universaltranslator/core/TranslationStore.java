package org.universaltranslator.core;

/** Cache abstraction shared by memory-only and privacy-conscious disk stores. */
public interface TranslationStore {
    String get(String key);

    void put(String key, String value);

    void clear();

    /**
     * Writes anything {@link #put(String, String)} deliberately deferred.
     *
     * <p>A store may coalesce writes, so the owner calls this once before dropping the store to make
     * the last changes durable. Memory-only stores have nothing to write and keep the default.
     */
    default void flush() {
    }
}
