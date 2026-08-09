package org.universaltranslator.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe bounded in-memory LRU cache. A disk-backed layer will wrap this later. */
public final class TranslationCache implements TranslationStore {
    private final Map<String, String> entries;

    public TranslationCache(final int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.entries = new LinkedHashMap<String, String>(16, 0.75f, true) {
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

    public synchronized int size() {
        return entries.size();
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }
}
