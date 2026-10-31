package org.universaltranslator.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe bounded in-memory cache that evicts the entry written earliest.
 *
 * <p>Insertion order, not access order, for the reason {@link TranslationStore} spells out: an
 * access-ordered map relinks the entry on every read, so a hit becomes a structural modification
 * inside the instance monitor that every worker shares, and the disk-backed store was already built
 * the other way round. The two implementations of one interface must not disagree on whether a read
 * writes.
 */
public final class TranslationCache implements TranslationStore {
    private final Map<String, String> entries;

    public TranslationCache(final int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.entries = new LinkedHashMap<String, String>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maximumEntries;
            }
        };
    }

    @Override
    public synchronized String get(String key) {
        return entries.get(key);
    }

    @Override
    public synchronized void put(String key, String value) {
        entries.put(key, value);
    }

    @Override
    public synchronized void remove(String key) {
        entries.remove(key);
    }

    public synchronized int size() {
        return entries.size();
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }
}
