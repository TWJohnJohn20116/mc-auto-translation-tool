package org.universaltranslator.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Small disk cache that hashes source/cache keys before persistence. Translated values remain local.
 * Disk write failures never break translation and the in-memory value remains usable.
 */
public final class PersistentTranslationCache implements TranslationStore {
    private final Path file;
    private final Map<String, String> entries;

    public PersistentTranslationCache(Path file, final int maximumEntries) throws IOException {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.file = file;
        this.entries = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maximumEntries;
            }
        };
        load();
    }

    @Override
    public synchronized String get(String key) {
        return entries.get(hash(key));
    }

    @Override
    public synchronized void put(String key, String value) {
        entries.put(hash(key), value);
        persistBestEffort();
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        persistBestEffort();
    }

    public synchronized int size() {
        return entries.size();
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            try {
                properties.load(reader);
            } catch (IllegalArgumentException malformedCache) {
                // A truncated Properties escape must not prevent the mod from starting.
                return;
            }
        }
        for (String key : properties.stringPropertyNames()) {
            entries.put(key, properties.getProperty(key));
        }
    }

    private void persistBestEffort() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Properties properties = new Properties();
            properties.putAll(entries);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "MC Auto Translation Tool cache; source keys are SHA-256 hashes");
            }
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Translation must remain available even when the cache directory is read-only.
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                output.append(String.format("%02x", item & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
