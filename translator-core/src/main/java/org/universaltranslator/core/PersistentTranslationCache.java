package org.universaltranslator.core;

import org.universaltranslator.core.net.CryptoSupport;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small disk cache that hashes source/cache keys before persistence. Translated values remain local.
 * Disk write failures never break translation and the in-memory value remains usable.
 *
 * <p>Concurrent {@link #put(String, String)} calls are coalesced instead of each rewriting the whole
 * file, and a burst of entries waits for up to {@code UNSAVED_ENTRIES_BEFORE_FLUSH} changes or
 * {@code FLUSH_INTERVAL_MILLIS} before the next rewrite. The caller that finds no flush running becomes the single writer; mutations arriving while
 * that flush is in flight only set a dirty flag, and the writer re-snapshots the map once its write
 * finishes. There is no background thread, no timer and no pending-write queue, so the class cannot
 * leak a thread past its owner and nothing can grow without bound. The only deferred state is the
 * dirty flag plus the in-memory LRU map itself.
 *
 * <p>The file is read lazily on the first {@link #get(String)}, {@link #put(String, String)},
 * {@link #remove(String)}, {@link #size()} or {@link #clear()} instead of in the constructor. Callers
 * build this cache while handling a settings change on the client tick thread, whereas the first
 * lookup normally happens on a worker thread, so the constructor performs no disk I/O at all.
 */
public final class PersistentTranslationCache implements TranslationStore {
    /** Changed entries tolerated before a rewrite is forced. */
    private static final int UNSAVED_ENTRIES_BEFORE_FLUSH = 64;
    /** Longest a changed entry may wait for the next rewrite, in milliseconds. */
    private static final long FLUSH_INTERVAL_MILLIS = 5_000L;

    /**
     * One lock per cache file path rather than one per instance. {@code initialize()} on a settings
     * change builds a new cache while the previous session is still alive, so two instances routinely
     * point at the same file; a per-instance lock let both of them write the same temporary file and
     * then move that file onto the target. Entries are never removed from this map, which is what
     * makes the lock object stable for the life of the process.
     */
    private static final ConcurrentHashMap<String, Object> PATH_LOCKS =
            new ConcurrentHashMap<String, Object>();

    private final Path file;
    /** Key of {@link #file} in {@link #PATH_LOCKS}, identical for every instance on that path. */
    private final String pathKey;
    private final Map<String, String> entries;

    /**
     * Guarded by {@code this}. True once the file has been consulted, whether the read succeeded,
     * failed or was skipped because the file does not exist. Never reset: {@link #clear()} sets it
     * so a cleared cache cannot read the deleted entries back from disk.
     */
    private boolean loaded;
    /**
     * Guarded by {@code this}. True when the file exists but could not be read. Such a session never
     * writes: it knows only the entries it translated itself, so persisting them would replace a full
     * cache file with a near-empty one. Never reset, so a single failed read keeps the file intact
     * until the next session reads it again.
     */
    private boolean loadFailed;
    /** Guarded by {@code this}. Bumped by every in-memory mutation, including {@link #clear()}. */
    private long revision;
    /** Guarded by {@code this}. True while a thread is running {@link #flushLoop}. */
    private boolean flushing;
    /** Guarded by {@code this}. True when a mutation arrived while a flush was running. */
    private boolean dirty;
    /** Guarded by {@code this}. Entries written to memory but not yet to the file. */
    private int unsaved;
    /** Guarded by {@code this}. When the file was last rewritten, in wall-clock milliseconds. */
    private long lastFlushMillis;
    /**
     * Guarded by the {@link #PATH_LOCKS} entry of {@link #pathKey}, and per instance: each cache
     * keeps its own revision sequence, the shared lock only orders the writes. Revision of the newest
     * state already written to the file.
     */
    private long persistedRevision;

    /**
     * The signature keeps {@code IOException} for every existing platform caller, but this
     * constructor never touches the disk: the file is read by {@link #ensureLoadedLocked()} on the
     * first lookup instead.
     */
    public PersistentTranslationCache(Path file, final int maximumEntries) throws IOException {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.file = file;
        // Normalized so that "config/cache.properties" and "config\..\config\cache.properties" from
        // two different callers resolve to the same PATH_LOCKS entry and therefore the same monitor.
        this.pathKey = file.toAbsolutePath().normalize().toString();
        // Insertion order, not access order: every worker thread reads through get(), and an
        // access-ordered map re-links the entry it reads, which turns a hit into a structural
        // modification under the instance monitor and serializes all workers behind it.
        this.entries = new LinkedHashMap<String, String>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maximumEntries;
            }
        };
    }

    @Override
    public synchronized String get(String key) {
        ensureLoadedLocked();
        return entries.get(hash(key));
    }

    @Override
    public void put(String key, String value) {
        Map<String, String> snapshot;
        long snapshotRevision;
        synchronized (this) {
            ensureLoadedLocked();
            String hashed = hash(key);
            String previous = entries.get(hashed);
            if (previous != null && previous.equals(value)) {
                // Identical mapping: memory and file already agree, so a rewrite would change nothing.
                return;
            }
            entries.put(hashed, value);
            snapshotRevision = ++revision;
            unsaved++;
            if (flushing) {
                // Another thread is already writing. Mark the state dirty and let that writer fold
                // this entry into its next pass instead of starting a second concurrent rewrite.
                dirty = true;
                return;
            }
            // Coalesce a burst instead of rewriting the whole file once per entry. The map holds up
            // to the configured maximum (ten thousand entries for the shipped setting), so writing
            // on every put made one session write O(n^2) bytes and kept a translation worker in the
            // file system instead of translating. Deferred entries are written by the next put past
            // either bound, by clear(), or by flush() when the owning session closes.
            long now = System.currentTimeMillis();
            if (unsaved < UNSAVED_ENTRIES_BEFORE_FLUSH
                    && now - lastFlushMillis < FLUSH_INTERVAL_MILLIS) {
                return;
            }
            flushing = true;
            unsaved = 0;
            lastFlushMillis = now;
            // The snapshot below already carries every change counted so far, so there is nothing
            // for flushLoop to fold in unless a new mutation arrives while the write runs.
            dirty = false;
            snapshot = snapshot();
        }
        flushLoop(snapshot, snapshotRevision);
    }

    @Override
    public void remove(String key) {
        Map<String, String> snapshot;
        long snapshotRevision;
        synchronized (this) {
            ensureLoadedLocked();
            if (entries.remove(hash(key)) == null) {
                // Nothing stored under this key, so memory and file already agree.
                return;
            }
            snapshotRevision = ++revision;
            unsaved++;
            if (flushing) {
                // Fold the removal into the running writer's next pass, exactly like an added entry.
                dirty = true;
                return;
            }
            // Same coalescing as put(): a revocation is one more changed entry, and giving it a
            // separate write path would reintroduce the rewrite-per-change cost put() exists to
            // avoid. The bumped revision is what makes a later flush() carry the removal even when it
            // stays deferred below either bound.
            long now = System.currentTimeMillis();
            if (unsaved < UNSAVED_ENTRIES_BEFORE_FLUSH
                    && now - lastFlushMillis < FLUSH_INTERVAL_MILLIS) {
                return;
            }
            flushing = true;
            unsaved = 0;
            lastFlushMillis = now;
            dirty = false;
            snapshot = snapshot();
        }
        flushLoop(snapshot, snapshotRevision);
    }

    @Override
    public void clear() {
        Map<String, String> snapshot;
        long snapshotRevision;
        synchronized (this) {
            // Mark the file as already consulted before dropping the entries. Without this a later
            // put would lazily read the file back and resurrect everything the user just deleted.
            loaded = true;
            entries.clear();
            // Clearing is an explicit user action, so this thread writes the empty map itself and
            // only returns once the file reflects it. A flush still in flight carries an older
            // revision and is dropped by persistBestEffort, so it cannot resurrect deleted entries.
            // The one exception is a session whose read failed: it declines to write at all, and the
            // file keeps whatever it holds until a session that can read it clears it.
            dirty = false;
            unsaved = 0;
            lastFlushMillis = System.currentTimeMillis();
            snapshotRevision = ++revision;
            snapshot = snapshot();
        }
        persistBestEffort(snapshot, snapshotRevision);
    }

    /**
     * Writes the entries {@link #put(String, String)} deferred. Called by the owning session before
     * the cache is dropped, so a clean shutdown persists a burst that never reached either bound.
     */
    @Override
    public void flush() {
        Map<String, String> snapshot;
        long snapshotRevision;
        synchronized (this) {
            if (!loaded || revision == 0L) {
                return;
            }
            if (flushing) {
                // The running writer re-reads the map once it finishes, so it will carry this state.
                dirty = true;
                return;
            }
            flushing = true;
            unsaved = 0;
            lastFlushMillis = System.currentTimeMillis();
            dirty = false;
            snapshotRevision = revision;
            snapshot = snapshot();
        }
        flushLoop(snapshot, snapshotRevision);
    }

    public synchronized int size() {
        ensureLoadedLocked();
        return entries.size();
    }

    /**
     * Writes the given snapshot and repeats while mutations arrived in the meantime. Only the calling
     * thread works here, and the pending state is a single flag, so nothing can accumulate.
     */
    private void flushLoop(Map<String, String> snapshot, long snapshotRevision) {
        try {
            while (true) {
                persistBestEffort(snapshot, snapshotRevision);
                synchronized (this) {
                    if (!dirty) {
                        // Clearing the flag and releasing ownership in one critical section means a
                        // concurrent put either marks this loop dirty or becomes the next writer.
                        flushing = false;
                        return;
                    }
                    dirty = false;
                    snapshot = snapshot();
                    snapshotRevision = revision;
                }
            }
        } catch (RuntimeException | Error failure) {
            // A write that fails unexpectedly must not latch the flush flag, which would silently
            // stop all further persistence for the lifetime of this cache.
            synchronized (this) {
                flushing = false;
            }
            throw failure;
        }
    }

    /**
     * Performs the deferred read exactly once. Caller must hold the monitor of {@code this}: the
     * flag is set before the read starts, so no two threads can read the file and no thread can
     * observe a half-loaded map. The flag is also set when the read fails, which keeps a permanently
     * unreadable file from retrying disk I/O on every lookup; an unreadable cache is treated as an
     * empty one, so translation still works and only the cached entries are lost.
     *
     * <p>A missing file is a normal first run: the cache starts empty and the next write creates the
     * file. A file that exists but cannot be parsed is the opposite case, because its entries are
     * unknown rather than absent. That difference is what {@link #loadFailed} records: the session
     * keeps translating into memory but stops writing, so the file it could not read survives as it is.
     */
    private void ensureLoadedLocked() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            load();
        } catch (IOException unreadableCache) {
            // get/put/clear cannot declare IOException without breaking every platform caller. The
            // constructor used to propagate this, so a corrupt or locked cache file now degrades to
            // an empty cache instead of failing the settings change that built this instance.
            markLoadFailed(unreadableCache);
        } catch (IllegalArgumentException malformedCache) {
            // A truncated Properties escape must not prevent the mod from starting, and
            // Properties.load reports one as an unchecked exception rather than an IOException.
            markLoadFailed(malformedCache);
        }
    }

    /**
     * Records an unusable cache file and turns persistence off for this session. Translation keeps
     * running from memory, which is the cheap side of the trade: overwriting the file with the empty
     * or partial map a failed read produced would throw away everything the file still holds.
     */
    private void markLoadFailed(Exception failure) {
        loadFailed = true;
        DebugLog.global().logFailure("cache-load", failure);
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (String key : properties.stringPropertyNames()) {
            entries.put(key, properties.getProperty(key));
        }
    }

    /**
     * Best-effort write of one snapshot. A snapshot older than what already reached the file is
     * skipped, so a flush that was in flight while {@link #clear()} ran cannot undo the clear.
     *
     * <p>Writes nothing once the file could not be read, which is checked before the path lock is
     * taken so the flag keeps the same monitor as the rest of the state without nesting two locks.
     */
    private void persistBestEffort(Map<String, String> snapshot, long snapshotRevision) {
        if (persistenceUnavailable()) {
            return;
        }
        synchronized (lockForPath()) {
            if (snapshotRevision < persistedRevision) {
                return;
            }
            Path temporary = null;
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                // Unique per attempt. Writers on the same path used to share one "<file>.tmp" name,
                // and the default TRUNCATE_EXISTING of newBufferedWriter let the later writer cut off
                // the file the earlier one was still filling before both moved it into place.
                temporary = file.resolveSibling(
                        file.getFileName().toString() + "." + UUID.randomUUID() + ".tmp");
                Properties properties = new Properties();
                properties.putAll(snapshot);
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    properties.store(writer, "MC Auto Translation Tool cache; source keys are SHA-256 hashes");
                }
                if (moveIntoPlace(temporary)) {
                    persistedRevision = snapshotRevision;
                }
            } catch (IOException ignored) {
                // Translation must remain available even when the cache directory is read-only.
            } finally {
                // A write we gave up on must not leave a file behind, and deleting the already moved
                // name is a no-op. The previous cache file is untouched either way.
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // Cannot be helped; the name is unique so the leftover is never read again.
                    }
                }
            }
        }
    }

    /**
     * Whether this session must not write. Synchronized because {@link #loadFailed} is guarded by the
     * same monitor as the rest of the state.
     */
    private synchronized boolean persistenceUnavailable() {
        return loadFailed;
    }

    /**
     * The lock shared by every instance pointing at this cache file.
     *
     * <p>{@code computeIfAbsent} stores the object for the life of the process, so two threads asking
     * for the same path always end up on the same monitor.
     */
    private Object lockForPath() {
        return PATH_LOCKS.computeIfAbsent(pathKey, key -> new Object());
    }

    /**
     * Moves a finished temporary file onto the cache file.
     *
     * @param temporary completed temporary file, unique to this attempt
     * @return {@code true} when the target file now holds this snapshot
     * @throws IOException when the move failed for a reason the caller should not ignore
     */
    private boolean moveIntoPlace(Path temporary) throws IOException {
        try {
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // A filesystem without atomic rename, such as some network shares. The temporary file
                // is already complete, so a plain replace is close enough to atomic for a cache.
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (NoSuchFileException race) {
            // NoSuchFileException does not say which side was missing. The temporary name belongs to
            // this attempt alone, so a source that is already gone was removed from outside this
            // cache and the write is simply lost, while a source that is still there means the
            // destination side vanished and this really is a failure.
            if (!Files.exists(temporary)) {
                return false;
            }
            throw race;
        }
        return true;
    }

    /** Caller must hold the monitor of {@code this}. */
    private Map<String, String> snapshot() {
        return new LinkedHashMap<String, String>(entries);
    }

    /** Lowercase SHA-256 hex of the key, identical to the previous hand-rolled hex loop. */
    private static String hash(String value) {
        return CryptoSupport.sha256Hex(value);
    }
}
