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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
 * dirty flag plus the in-memory LRU map itself. Durability therefore depends on the owner calling
 * {@link #flush()} before it drops the store: a deferred burst has no other writer, so a store that
 * is discarded without that call loses everything that had not reached either bound yet.
 *
 * <p>Once {@code maximumEntries} is reached an entry is dropped in insertion order, not in least
 * recently used order. Which entry a full cache discards is an implementation detail of this store
 * and not part of {@link TranslationStore}, so a caller must never depend on it.
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
     * Bytes one stored entry may occupy in the file. A 64 character digest, one translated value,
     * {@code =} and a line break -- counted with the escaping {@code Properties.store} applies, which
     * writes every non-ASCII character of a Chinese or Japanese value as {@code \}{@code uXXXX}, so a
     * value of about 160 characters is already close to this bound.
     */
    private static final long MAXIMUM_BYTES_PER_ENTRY = 1024L;
    /** Length of a {@link #hash(String)} result, which is the only key shape this class writes. */
    private static final int HASH_HEX_LENGTH = 64;
    /** Longest {@link #flush()} waits for a read that is still in flight before giving up. */
    private static final long LOAD_WAIT_MILLIS = 2_000L;

    /**
     * One state object per cache file path rather than one lock per instance. {@code initialize()} on
     * a settings change builds a new cache while the previous session is still alive, so two
     * instances routinely point at the same file; a per-instance lock let both of them write the same
     * temporary file and then move that file onto the target. Entries are never removed from this
     * map, which is what makes the monitor stable for the life of the process, and the generation
     * counter it carries is what lets a late writer notice that the file moved on without it.
     */
    private static final ConcurrentHashMap<String, PathState> PATH_LOCKS =
            new ConcurrentHashMap<String, PathState>();

    /**
     * One instance per cache file path, handed out by {@link #shared(Path, int)}. A settings change
     * builds a replacement session while the previous one is still shutting down, and both of them
     * used to parse the whole file again on their first lookup even though the path and the content
     * were identical.
     */
    private static final ConcurrentHashMap<String, PersistentTranslationCache> SHARED =
            new ConcurrentHashMap<String, PersistentTranslationCache>();

    private final Path file;
    /** Key of {@link #file} in {@link #PATH_LOCKS}, identical for every instance on that path. */
    private final String pathKey;
    private final Map<String, String> entries;
    /** How many entries this instance keeps; also the cap on {@link #revokedKeys}. */
    private final int maximumEntries;
    /**
     * Hashed keys this instance removed on purpose -- a rollback of a value that failed the display
     * check, or a user pressing "clear". A merge with the file must not bring them back, so they are
     * remembered here. Concurrent because the merge runs under the path monitor while the removal
     * happens under the instance monitor.
     */
    private final Set<String> revokedKeys =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

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
    /**
     * Guarded by {@code this}. Bumped by {@link #clear()} alone, so the deferred read can tell that
     * the entries were dropped while it was reading the file and keep its result out of the map.
     */
    private long clearGeneration;
    /**
     * Guarded by {@code this}. True while the deferred read runs. No writer may run with it set: the
     * map it would persist is missing every entry the file still holds.
     */
    private boolean loading;
    /** Guarded by {@code this}. True while a thread is running {@link #flushLoop}. */
    private boolean flushing;
    /** Guarded by {@code this}. True when a mutation arrived while a flush was running. */
    private boolean dirty;
    /** Guarded by {@code this}. Entries written to memory but not yet to the file. */
    private int unsaved;
    /** Guarded by {@code this}. When the file was last rewritten, in wall-clock milliseconds. */
    private long lastFlushMillis;
    /**
     * Guarded by the {@link #PATH_LOCKS} entry of {@link #pathKey}: revision of the newest state this
     * instance wrote. It only orders this instance's own writes; across instances the file's
     * {@link PathState#generation} is what decides whether a snapshot may replace the file or has to
     * be merged into it.
     */
    private long persistedRevision;
    /**
     * Guarded by the path monitor. The file generation this instance last read or wrote. When the
     * shared generation has moved past it, another instance wrote the file in the meantime and this
     * instance's snapshot is no longer a complete view of it.
     */
    private long observedGeneration;
    /**
     * Guarded by the path monitor. The clear epoch this instance last saw. A different value means
     * the file was emptied on purpose after this instance last looked at it, so a merge would bring
     * back everything the user deleted.
     */
    private long observedClearEpoch;
    /** Guarded by the path monitor. Counts failed writes; only the first one reaches the log. */
    private int failedWrites;

    /**
     * The signature keeps {@code IOException} for every existing platform caller, but this
     * constructor never touches the disk: the file is read by {@link #ensureLoaded()} on the
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
        this.maximumEntries = maximumEntries;
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

    /**
     * The store for {@code file}, created on first use and then shared by every later caller.
     *
     * <p>Retiring a session on a settings change used to build a fresh instance, whose first lookup
     * parsed the whole file again -- up to {@code maximumEntries} properties, tens of milliseconds on
     * a network-synced configuration folder -- for a file that had not changed. {@link #PATH_LOCKS}
     * already serializes the writers of one path, so one instance per path is the shape this class
     * was built for; {@link #clear()} and the merge path already carry their state per path rather
     * than per instance.
     *
     * <p>The caller that creates the instance decides {@code maximumEntries}; a later caller on the
     * same path gets the existing instance. Entries are never removed from this map, exactly like
     * {@link #PATH_LOCKS}, so the identity a caller received stays valid for the process.
     *
     * @param file cache file, not {@code null}
     * @param maximumEntries entries to keep, as for {@link #PersistentTranslationCache(Path, int)}
     * @return the store of that path
     * @throws IOException never thrown today, kept because the constructor declares it
     */
    public static TranslationStore shared(Path file, final int maximumEntries) throws IOException {
        String key = file.toAbsolutePath().normalize().toString();
        PersistentTranslationCache existing = SHARED.get(key);
        if (existing != null) {
            return existing;
        }
        PersistentTranslationCache created = new PersistentTranslationCache(file, maximumEntries);
        PersistentTranslationCache raced = SHARED.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    @Override
    public String get(String key) {
        // Hashed before the monitor: the key carries the whole source text, and a SHA-256 computed
        // under the instance monitor serialized every worker on work that shares no state.
        String hashed = hash(key);
        ensureLoaded();
        synchronized (this) {
            return entries.get(hashed);
        }
    }

    @Override
    public void put(String key, String value) {
        String hashed = hash(key);
        Map<String, String> snapshot;
        long snapshotRevision;
        ensureLoaded();
        synchronized (this) {
            String previous = entries.get(hashed);
            if (previous != null && previous.equals(value)) {
                // Identical mapping: memory and file already agree, so a rewrite would change nothing.
                return;
            }
            entries.put(hashed, value);
            // Storing a value again withdraws an earlier revocation of the same key, so a merge
            // must not drop it.
            revokedKeys.remove(hashed);
            snapshotRevision = ++revision;
            unsaved++;
            if (loading) {
                // The deferred read is still running, and the map it would persist is missing every
                // entry the file holds. The next put past either bound writes this one instead.
                return;
            }
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
        String hashed = hash(key);
        Map<String, String> snapshot;
        long snapshotRevision;
        ensureLoaded();
        synchronized (this) {
            if (entries.remove(hashed) == null) {
                // Nothing stored under this key, so memory and file already agree.
                return;
            }
            // Remembered so that the merge in persistBestEffort cannot read the value back from a
            // file another instance has written since; the cap keeps a long session bounded, and a
            // snapshot that carries more revocations than entries is replaced wholesale anyway.
            if (revokedKeys.size() < maximumEntries) {
                revokedKeys.add(hashed);
            }
            snapshotRevision = ++revision;
            unsaved++;
            if (loading) {
                // Same reason as put(): a revocation measured against a half-loaded map would write
                // a file with the stored entries still in it and the revoked one only in memory.
                return;
            }
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
            clearGeneration++;
            // Nothing is left to protect: with the entries gone there is no stored value a merge
            // could bring back, and keeping the revocations only made every later merge scan them.
            revokedKeys.clear();
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
        // Deliberately not merged with the file: the user asked for the entries to be gone, so the
        // empty snapshot must replace whatever is there instead of adopting it back. The clear epoch
        // is published by that same write, inside its path critical section: bumping it afterwards
        // left a window in which another instance saw the new generation next to the old epoch and
        // merged every deleted entry back into the file.
        persistBestEffort(snapshot, snapshotRevision, false, true);
    }

    /**
     * Writes the entries {@link #put(String, String)} deferred. Called by the owning session before
     * the cache is dropped, so a clean shutdown persists a burst that never reached either bound.
     *
     * <p>This call is the only chance the deferred entries get: nothing else in this class writes
     * them, so an owner that drops the store without it loses them without any log line. That holds
     * whatever the state of the owner's own workers -- a worker still running can only add entries,
     * and those are written by the next flush.
     */
    @Override
    public void flush() {
        Map<String, String> snapshot;
        long snapshotRevision;
        synchronized (this) {
            if (!loaded) {
                return;
            }
            // A read that is still running is not a reason to drop this session's burst: the file's
            // own entries are what that read publishes, while what this write carries is the entries
            // memory holds and the file does not. Waiting for it keeps the "the owner calls this once
            // before dropping the store" contract of TranslationStore#flush, with a bounded wait so a
            // read stuck on a slow filesystem cannot hang the shutdown that owns this store.
            if (waitForLoadToFinish()) {
                return;
            }
            if (revision == 0L) {
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

    /**
     * Waits for the deferred read to publish, so a writer never snapshots a map that is still missing
     * the entries the file holds.
     *
     * <p>The caller holds this monitor; {@link Object#wait(long)} releases it, which is what lets
     * {@link #ensureLoaded()} publish and wake this thread up.
     *
     * @return {@code true} when the caller must not write: it was interrupted, or the read outlived
     *     {@link #LOAD_WAIT_MILLIS}
     */
    private boolean waitForLoadToFinish() {
        long deadline = System.currentTimeMillis() + LOAD_WAIT_MILLIS;
        while (loading) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                return true;
            }
            try {
                wait(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    public int size() {
        ensureLoaded();
        synchronized (this) {
            return entries.size();
        }
    }

    /**
     * Writes the given snapshot and repeats while mutations arrived in the meantime. Only the calling
     * thread works here, and the pending state is a single flag, so nothing can accumulate.
     */
    private void flushLoop(Map<String, String> snapshot, long snapshotRevision) {
        try {
            while (true) {
                persistBestEffort(snapshot, snapshotRevision, true, false);
                synchronized (this) {
                    if (!dirty) {
                        // Clearing the flag and releasing ownership in one critical section means a
                        // concurrent put either marks this loop dirty or becomes the next writer.
                        flushing = false;
                        return;
                    }
                    // A mutation that lands inside the write window used to force an immediate second
                    // whole-file rewrite, and every put during that rewrite forced another one, so a
                    // busy session rewrote ten thousand entries far more often than the coalescing
                    // bounds allow. The next pass waits for those same bounds; the deferred entry
                    // stays in the map, which every writer snapshots as a whole.
                    long now = System.currentTimeMillis();
                    if (unsaved < UNSAVED_ENTRIES_BEFORE_FLUSH
                            && now - lastFlushMillis < FLUSH_INTERVAL_MILLIS) {
                        dirty = false;
                        flushing = false;
                        return;
                    }
                    dirty = false;
                    unsaved = 0;
                    lastFlushMillis = now;
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
     * Performs the deferred read exactly once. The flag is set before the read starts, so no two
     * threads can read the file and the flag also keeps a permanently unreadable file from retrying
     * disk I/O on every lookup; an unreadable cache is treated as an empty one, so translation still
     * works and only the cached entries are lost.
     *
     * <p>The read itself runs without the instance monitor. It used to hold it for the whole file,
     * so the first lookup of a session made every other worker wait for a ten thousand entry parse;
     * a lookup that arrives during the read now simply misses and asks the provider.
     *
     * <p>A missing file is a normal first run: the cache starts empty and the next write creates the
     * file. A file that exists but cannot be parsed is the opposite case, because its entries are
     * unknown rather than absent. That difference is what {@link #loadFailed} records: the session
     * keeps translating into memory but stops writing, so the file it could not read survives as it is.
     */
    private void ensureLoaded() {
        long readingGeneration;
        synchronized (this) {
            if (loaded) {
                return;
            }
            loaded = true;
            loading = true;
            readingGeneration = clearGeneration;
        }
        Map<String, String> fromFile = null;
        try {
            fromFile = load();
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
        synchronized (this) {
            loading = false;
            // A flush() that arrived while this read was in flight waits on this monitor; without the
            // wake-up it would sit out its whole deadline and then drop the session's burst anyway.
            notifyAll();
            if (fromFile == null || clearGeneration != readingGeneration) {
                // The read failed, or the entries were cleared while it was in flight. Publishing
                // now would put back everything the user deleted, which is the one thing the flag
                // behind "a cleared cache cannot read the deleted entries back" has to prevent.
                return;
            }
            for (Map.Entry<String, String> entry : fromFile.entrySet()) {
                // A value translated while the file was being read is newer than the stored one.
                if (!entries.containsKey(entry.getKey())) {
                    entries.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * Records an unusable cache file and turns persistence off for this session. Translation keeps
     * running from memory, which is the cheap side of the trade: overwriting the file with the empty
     * or partial map a failed read produced would throw away everything the file still holds.
     */
    private synchronized void markLoadFailed(Exception failure) {
        loadFailed = true;
        DebugLog.global().logFailure("cache-load", failure);
    }

    /**
     * Reads the file into a map without touching the instance state.
     *
     * <p>The returned map is published by {@link #ensureLoaded()} under the instance monitor; only
     * the path-level generation is read here, so two instances on one file still see each other's
     * writes as "the file moved on".
     *
     * @return the stored entries, empty when there is no file yet
     * @throws IOException when the file exists but cannot be read
     */
    private Map<String, String> load() throws IOException {
        Map<String, String> fromFile = Collections.emptyMap();
        PathState state = stateForPath();
        synchronized (state.monitor) {
            if (Files.exists(file)) {
                long storedBytes = Files.size(file);
                if (storedBytes > maximumEntries * MAXIMUM_BYTES_PER_ENTRY) {
                    // Properties.load reads the whole file into a Hashtable and the map below copies it
                    // once more, so a file this large costs two to three times its size in transient
                    // objects -- on the first lookup of a session, i.e. on a render thread or a
                    // worker. Nothing this class writes can reach that size within its entry cap, so
                    // the file is treated like an unreadable one: memory keeps working, the file is
                    // left as it is for a session that can read it, and it is logged once.
                    throw new IOException("cache file exceeds the entry cap: " + storedBytes
                            + " bytes for " + maximumEntries + " entries");
                }
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                fromFile = new LinkedHashMap<String, String>(
                        Math.min(properties.size(), maximumEntries));
                for (String key : properties.stringPropertyNames()) {
                    if (fromFile.size() >= maximumEntries) {
                        break;
                    }
                    // hash() is the only writer of this file and it always produces a digest of exactly
                    // this length, so any other key is debris of a truncated or half-written file.
                    // Loading it would keep it in memory and let the next write carry it forward.
                    if (key.length() != HASH_HEX_LENGTH) {
                        continue;
                    }
                    fromFile.put(key, properties.getProperty(key));
                }
            }
            // Recorded after the read: a write that lands while this instance is loading bumps the
            // generation past this value, so the next flush merges instead of overwriting it.
            observedGeneration = state.generation;
            observedClearEpoch = state.clearEpoch;
        }
        return fromFile;
    }

    /**
     * Best-effort write of one snapshot.
     *
     * <p>A snapshot older than what this instance already wrote is skipped, so a flush that was in
     * flight while {@link #clear()} ran cannot undo the clear.
     *
     * <p>Two instances on one file are the normal state right after a settings change: the
     * replacement cache starts translating immediately while the retired session only reaches its
     * final {@code flush()} once its workers have drained. Writing the retired snapshot verbatim
     * replaced the file the live session had already grown, so everything translated between the
     * switch and that late flush vanished from disk with no log line anywhere. The revision numbers
     * of the two instances are unrelated sequences, so they cannot be compared; what can be compared
     * is the file's own {@link PathState#generation}. A snapshot is written as a whole only when no
     * other instance has written the file since this one last read or wrote it. Otherwise the file's
     * current entries are folded in first, so a late writer can add entries but never subtract them.
     *
     * <p>Writes nothing once the file could not be read, which is checked before the path lock is
     * taken so the flag keeps the same monitor as the rest of the state without nesting two locks.
     *
     * @param mergeWithFile {@code false} when the caller deliberately emptied the cache and the file
     *     must be replaced rather than adopted
     * @param bumpClearEpoch {@code true} when this write is the {@link #clear()} itself, so the epoch
     *     that tells other instances "this was emptied on purpose" is published inside the same path
     *     critical section as the write instead of after it
     */
    private void persistBestEffort(
            Map<String, String> snapshot,
            long snapshotRevision,
            boolean mergeWithFile,
            boolean bumpClearEpoch
    ) {
        if (persistenceUnavailable()) {
            return;
        }
        PathState state = stateForPath();
        synchronized (state.monitor) {
            if (snapshotRevision < persistedRevision) {
                return;
            }
            if (bumpClearEpoch) {
                // Bumped before the write so no other instance can observe the new generation next to
                // the old epoch and merge the deleted entries back in.
                state.clearEpoch++;
                observedClearEpoch = state.clearEpoch;
            }
            Map<String, String> contents = snapshot;
            if (mergeWithFile && state.generation != observedGeneration) {
                if (state.clearEpoch != observedClearEpoch) {
                    // The file was emptied on purpose after this instance last looked at it. Folding
                    // this snapshot in would put every entry back, so it is dropped instead.
                    return;
                }
                Map<String, String> onDisk = readExistingEntries();
                if (onDisk == null) {
                    // Another instance wrote a file this one cannot parse. Leaving it untouched is
                    // the only outcome that cannot lose the entries it holds.
                    return;
                }
                // The merged map is what the file will hold, so the entry cap has to apply here as
                // well: two instances on one file could otherwise write twice maximumEntries and let
                // the next session evict half of it in insertion order.
                contents = new LinkedHashMap<String, String>(
                        Math.min(maximumEntries, onDisk.size() + snapshot.size()) * 2);
                contents.putAll(snapshot);
                for (Map.Entry<String, String> entry : onDisk.entrySet()) {
                    if (contents.size() >= maximumEntries) {
                        break;
                    }
                    if (contents.containsKey(entry.getKey())
                            || revokedKeys.contains(entry.getKey())) {
                        continue;
                    }
                    contents.put(entry.getKey(), entry.getValue());
                }
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
                properties.putAll(contents);
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    properties.store(writer, "MC Auto Translation Tool cache; source keys are SHA-256 hashes");
                }
                if (moveIntoPlace(temporary)) {
                    persistedRevision = snapshotRevision;
                    observedGeneration = ++state.generation;
                    observedClearEpoch = state.clearEpoch;
                }
            } catch (IOException failedWrite) {
                // Translation must remain available even when the cache directory is read-only. The
                // first failure is recorded anyway: a session whose entries never reach the file was
                // indistinguishable from one that simply had nothing due yet.
                if (failedWrites == 0) {
                    DebugLog.global().logFailure("cache-write", failedWrite);
                }
                failedWrites++;
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
     * The entries the file currently holds.
     *
     * <p>Only used on the rare merge path, where another instance has written the file since this one
     * last looked at it. Reading it back is the price of not overwriting what that instance added.
     *
     * @return the file's entries, or {@code null} when it exists but cannot be parsed
     */
    private Map<String, String> readExistingEntries() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<String, String>();
        }
        Properties properties = new Properties();
        try {
            // The same bound load() applies: the merge runs on the thread that is retiring a session,
            // and it must not materialize a file that cannot be one this cache wrote.
            if (Files.size(file) > maximumEntries * MAXIMUM_BYTES_PER_ENTRY) {
                return null;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException unreadable) {
            return null;
        } catch (RuntimeException malformed) {
            return null;
        }
        Map<String, String> existing = new LinkedHashMap<String, String>(
                Math.min(properties.size(), maximumEntries));
        for (String key : properties.stringPropertyNames()) {
            if (existing.size() >= maximumEntries) {
                break;
            }
            if (key.length() != HASH_HEX_LENGTH) {
                continue;
            }
            existing.put(key, properties.getProperty(key));
        }
        return existing;
    }

    /**
     * Whether this session must not write. Synchronized because {@link #loadFailed} is guarded by the
     * same monitor as the rest of the state.
     */
    private synchronized boolean persistenceUnavailable() {
        return loadFailed;
    }

    /**
     * The shared state for this cache file's path: its monitor and the generation of what the file
     * holds.
     *
     * <p>{@code computeIfAbsent} stores the object for the life of the process, so two threads asking
     * for the same path always end up on the same monitor and the same counter.
     */
    private PathState stateForPath() {
        return PATH_LOCKS.computeIfAbsent(pathKey, key -> new PathState());
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

    /**
     * Process-wide state of one cache file: the monitor its writers serialize on, plus a generation
     * counter bumped by every write that reached the file, whichever instance performed it.
     *
     * <p>The generation is what makes "the file is newer than my snapshot" observable across
     * instances, whose own revision sequences each start at zero and therefore cannot be compared.
     */
    private static final class PathState {
        private final Object monitor = new Object();
        /** Guarded by {@link #monitor}. Bumped by every write that reached the file. */
        private long generation;
        /**
         * Guarded by {@link #monitor}. Bumped when the file was deliberately emptied. The generation
         * alone cannot tell a merge that should adopt the file from one that must not resurrect it.
         */
        private long clearEpoch;
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
