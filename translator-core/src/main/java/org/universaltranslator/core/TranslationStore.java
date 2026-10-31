package org.universaltranslator.core;

/**
 * Cache abstraction shared by memory-only and privacy-conscious disk stores.
 *
 * <p>Eviction is <em>not</em> part of this contract. The bound and the order in which entries are
 * dropped are properties of each implementation, and callers must not depend on either: the in-memory
 * store evicts the least recently used entry, while the disk-backed store evicts the one written
 * earliest, because an access-ordered map relinks the entry on every read and that turns a hit into a
 * structural modification under the instance monitor every worker shares. A caller that needs an entry
 * to survive keeps it, or writes it again; {@link #get(String)} returning {@code null} is the only
 * thing it may conclude.
 */
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
