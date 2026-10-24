package org.universaltranslator.core;

/** Cache abstraction shared by memory-only and privacy-conscious disk stores. */
public interface TranslationStore {
    String get(String key);

    void put(String key, String value);

    /**
     * Forgets one stored mapping.
     *
     * <p>Used to revoke a write that was already committed but was afterwards found to be unusable,
     * so the bad value cannot be served again from memory or, for a disk-backed store, from the file.
     * Removing a key that is not stored is a no-op.
     *
     * @param key the source key that was passed to {@link #put(String, String)}
     */
    void remove(String key);

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
