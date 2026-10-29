package org.universaltranslator.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Opt-in request tracing for support, written to {@code config/universal-translator-debug.log}.
 *
 * <p>Every value that reaches the file goes through {@link #redact(String)}, so an API key, an
 * {@code Authorization} header, an {@code sk-} token or a long base64 run cannot be written even if
 * a caller passes one by accident. The log is bounded: once it passes {@link #MAXIMUM_BYTES} the
 * current file is moved to {@code universal-translator-debug.log.1} and a new one is started, so a
 * long session keeps one rotated file rather than growing without bound.
 *
 * <p>When the setting is off every method returns before doing any work, which is why the debug
 * mode cannot change behaviour while it is disabled. When it is on, lines are queued and written in
 * batches — consecutive stream deltas are folded into one line and the queue is drained by a daemon
 * thread every {@link #FLUSH_INTERVAL_MILLIS} — so tracing a streamed answer costs a handful of file
 * system calls instead of one per token. The queue is bounded and drops rather than applies back
 * pressure: an unwritable file, or a batch the flusher cannot keep up with, is ignored rather than
 * failing or delaying a translation. {@link #flush()} writes the tail on demand.
 *
 * <p>Lines recorded before the platform calls {@link #configure} are kept in a separate bounded
 * buffer, because the configuration is read before the log can be switched on: without it the debug
 * log could never show the fallback records of the startup that was reading the configuration.
 */
public final class DebugLog {
    /** Sits next to {@code universal-translator.properties} in the game instance's config folder. */
    public static final String FILE_NAME = "universal-translator-debug.log";
    /** The single rotated file kept beside the current log. */
    public static final String ROTATED_FILE_NAME = FILE_NAME + ".1";
    /** Size at which the current log is rotated. */
    public static final long MAXIMUM_BYTES = 2L * 1024L * 1024L;
    /** Longest text preview written for one request, in characters. */
    public static final int MAXIMUM_PREVIEW_CHARS = 120;

    /**
     * How often the queue is drained when nothing else forces a write.
     *
     * <p>This used to be per line: a local llama.cpp or relay stream sends 30-100 deltas per second,
     * and every line cost four file system calls ({@code createDirectories}, {@code exists},
     * {@code size}, append) while holding the monitor the render thread shares, so a single
     * 500-token answer meant roughly 2000 calls on the hot path. Buffering moves that to one
     * {@code size} plus one append per batch.
     */
    private static final long FLUSH_INTERVAL_MILLIS = 200L;
    /** Queued lines that trigger a write without waiting for {@link #FLUSH_INTERVAL_MILLIS}. */
    private static final int FLUSH_BATCH_LINES = 64;
    /** Upper bound on queued text; a support log drops rather than applies back pressure. */
    private static final int MAXIMUM_PENDING_CHARS = 64 * 1024;
    /** Upper bound on the lines kept before {@link #configure} has been called. */
    private static final int MAXIMUM_PRELUDE_CHARS = 8 * 1024;

    /**
     * A base64-ish run long enough to be a key rather than prose. It is deliberately broad: the
     * debug log is a support artefact, and over-redacting a preview costs far less than leaking a
     * credential.
     */
    private static final Pattern LONG_BASE64 = Pattern.compile("[A-Za-z0-9+/]{32,}={0,2}");

    /**
     * {@code name=value} for a name that ends in a credential word, whatever prefix it carries, so
     * {@code DeepL-Auth-Key} and {@code x-goog-api-key} are covered as well as {@code api-key}. A
     * trailing {@code -id} / {@code -key} / {@code -secret} is allowed because several providers
     * name the key {@code aliyun-access-key-id}.
     *
     * <p>The value may not start with {@code [} so that a placeholder this file already wrote — or
     * one the shared rules just wrote — is not redacted a second time.
     */
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?<![A-Za-z0-9_])[a-z0-9_.-]*(?:key|secret|token|password|credential)"
                    + "(?:[-_](?:id|key|secret))?\\s*[=:]\\s*(?:bearer\\s+)?)([^\\s\\[]\\S*)");

    /**
     * The same names separated by a space rather than a colon. The value has to look like a token
     * (16 characters or more), so prose such as "the token expired" is left readable.
     */
    private static final Pattern NAMED_SECRET_SPACED = Pattern.compile(
            "(?i)((?<![A-Za-z0-9_])[a-z0-9_.-]*(?:key|secret|token|password|credential)"
                    + "(?:[-_](?:id|key|secret))?\\s+)([^\\s\\[]\\S{15,})");

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /** The credential names {@link #NAMED_SECRET} and {@link #NAMED_SECRET_SPACED} match. */
    private static final String[] CREDENTIAL_NAMES = {
            "key", "secret", "token", "password", "credential"
    };

    private static final DebugLog INSTANCE = new DebugLog();

    private final Object lock = new Object();
    /**
     * Held only around the file system calls themselves, never together with {@link #lock}, so a
     * caller that queues a line cannot end up waiting for a disk write.
     */
    private final Object writeLock = new Object();
    private volatile Path file;
    private volatile boolean enabled;
    /** Whether {@link #configure} has run; until then events are kept for {@link #prelude}. */
    private volatile boolean configured;
    private final StringBuilder pending = new StringBuilder(1024);
    private int pendingLines;
    private int droppedLines;
    /** Characters lost because a batch could not be written; reported in the next batch. */
    private int droppedCharacters;
    private volatile boolean directoryReady;
    private Thread flusher;
    private final StringBuilder prelude = new StringBuilder(512);
    private String streamProvider;
    private String streamEvent;
    private int streamCount;
    private int streamCharacters;

    private DebugLog() {
    }

    /** The log every platform shares. */
    public static DebugLog global() {
        return INSTANCE;
    }

    /**
     * A separate log, so the self test does not touch the process-wide one.
     *
     * @return an unconfigured log
     */
    static DebugLog isolated() {
        return new DebugLog();
    }

    /**
     * Points the log at its file and switches it on or off.
     *
     * <p>Called by every platform while it loads its configuration. Turning the log on writes a
     * header line, which makes it obvious in the file when tracing started, and also writes out the
     * lines that were recorded while the log was still off — a hand-edited value that fell back to a
     * default is reported exactly there.
     *
     * @param logFile file to append to; {@code null} leaves the log disabled
     * @param debugEnabled whether the setting is on
     */
    public void configure(Path logFile, boolean debugEnabled) {
        String previousBatch;
        Path previousTarget;
        String batch;
        Path target;
        synchronized (lock) {
            boolean wasEnabled = enabled && file != null;
            previousBatch = null;
            previousTarget = null;
            if (wasEnabled) {
                // Whatever is still queued belongs to the file that is being replaced.
                sealStreamLocked();
                previousTarget = file;
                previousBatch = takeBatchLocked();
            }
            pending.setLength(0);
            pendingLines = 0;
            droppedLines = 0;
            droppedCharacters = 0;
            directoryReady = false;
            streamProvider = null;
            streamEvent = null;
            streamCount = 0;
            streamCharacters = 0;
            file = logFile;
            enabled = debugEnabled && logFile != null;
            configured = true;
            batch = null;
            target = null;
            if (!enabled) {
                prelude.setLength(0);
            } else {
                if (!wasEnabled) {
                    if (prelude.length() > 0) {
                        appendPendingLocked(prelude);
                        prelude.setLength(0);
                    }
                    appendLineLocked(TIMESTAMP.format(LocalDateTime.now())
                            + " session debug logging enabled");
                }
                target = file;
                batch = takeBatchLocked();
            }
            // A flusher parked for the next interval has to notice the new file, or the fact that
            // the log was turned off, without waiting for it.
            lock.notifyAll();
        }
        // The file system calls happen after the monitor was released so that no caller waits on
        // the disk; both batches belong to the file that was current when they were taken.
        writeBatch(previousBatch, previousTarget);
        writeBatch(batch, target);
        if (enabled) {
            // Started last, so the header written above cannot be overtaken by a later batch.
            synchronized (lock) {
                if (enabled) {
                    startFlusherLocked();
                }
            }
        }
    }

    /** Whether entries are being written. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Records one event. {@code fields} is a sequence of {@code name, value} pairs. */
    public void log(String event, String... fields) {
        boolean writable = enabled && file != null;
        if (!writable && configured) {
            return;
        }
        StringBuilder line = new StringBuilder(192);
        line.append(TIMESTAMP.format(LocalDateTime.now())).append(' ').append(event);
        if (fields != null) {
            for (int index = 0; index + 1 < fields.length; index += 2) {
                line.append(' ').append(fields[index]).append('=')
                        .append(redact(fields[index + 1]));
            }
        }
        synchronized (lock) {
            if (writable) {
                appendLineLocked(line.toString());
                if (pendingLines >= FLUSH_BATCH_LINES) {
                    // Only the flusher writes, so a caller on the render thread signals a ready
                    // batch instead of writing it and blocking the frame on the disk.
                    lock.notifyAll();
                }
            } else {
                // The configuration is read before configure(...) can run, so a record made now is
                // the only evidence of a value that fell back to its default. Keep it briefly.
                capturePreludeLocked(line);
            }
        }
    }

    /** Writes everything queued so far; the tail of a session is otherwise held for one interval. */
    public void flush() {
        String batch;
        Path target;
        synchronized (lock) {
            sealStreamLocked();
            target = file;
            batch = takeBatchLocked();
        }
        writeBatch(batch, target);
    }

    /** One provider request, with the fields support needs to reproduce it. */
    public void logRequest(
            String provider,
            String model,
            String endpoint,
            String kind,
            String text
    ) {
        if (!enabled) {
            return;
        }
        log("request", "provider", provider, "model", model, "host", hostOnly(endpoint),
                "kind", kind, "length", text == null ? "0" : Integer.toString(text.length()),
                "preview", preview(text));
    }

    /** A provider answered, with the length of the translation and its preview. */
    public void logResponse(String provider, String kind, String translated) {
        if (!enabled) {
            return;
        }
        log("response", "provider", provider, "kind", kind,
                "length", translated == null ? "0" : Integer.toString(translated.length()),
                "preview", preview(translated));
    }

    /** A cache lookup decided whether the provider was called at all. */
    public void logCache(boolean hit, String provider, String kind, String text) {
        if (!enabled) {
            return;
        }
        log(hit ? "cache-hit" : "cache-miss", "provider", provider, "kind", kind,
                "length", text == null ? "0" : Integer.toString(text.length()));
    }

    /** An attempt failed and a retry follows, with the delay that was applied. */
    public void logRetry(String provider, int attempt, long delayMillis, Throwable failure) {
        if (!enabled) {
            return;
        }
        log("retry", "provider", provider, "attempt", Integer.toString(attempt),
                "delay-ms", Long.toString(delayMillis),
                "reason", TranslationStats.reasonOf(failure),
                "error", failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    /** An attempt failed for good. */
    public void logFailure(String provider, Throwable failure) {
        if (!enabled) {
            return;
        }
        log("failure", "provider", provider, "reason", TranslationStats.reasonOf(failure),
                "error", failure == null ? "unknown" : failure.getClass().getSimpleName(),
                "message", failure == null ? "" : failure.getMessage());
    }

    /**
     * One event arrived from a streamed response.
     *
     * <p>Consecutive deltas of the same provider and event are folded into one line carrying
     * {@code count} and the summed {@code characters}, because a stream calls this once per token.
     * The line is written by the next flush, or immediately when a different event arrives.
     */
    public void logStreamEvent(String provider, String event, int characters) {
        if (!enabled || file == null) {
            return;
        }
        synchronized (lock) {
            if (streamCount > 0
                    && (!sameText(streamProvider, provider) || !sameText(streamEvent, event))) {
                sealStreamLocked();
            }
            streamProvider = provider;
            streamEvent = event;
            streamCount++;
            streamCharacters += Math.max(0, characters);
        }
    }

    private static boolean sameText(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    /** Turns the pending stream aggregate into one queued line. Caller holds {@link #lock}. */
    private void sealStreamLocked() {
        if (streamCount <= 0) {
            return;
        }
        String line = TIMESTAMP.format(LocalDateTime.now()) + " stream"
                + " provider=" + redact(streamProvider)
                + " event=" + redact(streamEvent)
                + " count=" + streamCount
                + " characters=" + streamCharacters;
        streamProvider = null;
        streamEvent = null;
        streamCount = 0;
        streamCharacters = 0;
        appendLineLocked(line);
    }

    /**
     * The redaction applied to every value written to the log.
     *
     * <p>Builds on {@link DiagnosticsLogExporter}'s rules — endpoints, {@code name=value}
     * assignments to credential-ish names, {@code Bearer} tokens and {@code sk-} keys — and adds a
     * bare long base64 run, which that rule set only catches when a credential name precedes it.
     *
     * @param value raw value, possibly {@code null}
     * @return the value with credentials replaced by placeholders
     */
    public static String redact(String value) {
        if (value == null) {
            return "";
        }
        String redacted = DiagnosticsLogExporter.sanitize(value);
        // Each rule below can only match a shape the value does not have in most log lines: a
        // credential name next to '=' or ':' or whitespace, and a run of at least 32 token
        // characters. Skipping a scan that cannot match leaves the result byte for byte identical,
        // and one request line carries eight numeric fields that used to pay for four passes each.
        if (mentionsCredentialName(redacted)) {
            if (redacted.indexOf('=') >= 0 || redacted.indexOf(':') >= 0) {
                redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1[key hidden]");
            }
            if (containsWhitespace(redacted)) {
                redacted = NAMED_SECRET_SPACED.matcher(redacted).replaceAll("$1[key hidden]");
            }
        }
        if (redacted.length() >= 32) {
            redacted = LONG_BASE64.matcher(redacted).replaceAll("[key hidden]");
        }
        return redacted;
    }

    /** Whether any of the credential names the two patterns look for occurs in the value. */
    private static boolean mentionsCredentialName(String value) {
        for (String word : CREDENTIAL_NAMES) {
            int last = value.length() - word.length();
            for (int index = 0; index <= last; index++) {
                if (value.regionMatches(true, index, word, 0, word.length())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The host of an endpoint and nothing else.
     *
     * <p>The exported report and the log both promise that endpoints are excluded, so only the host
     * survives. A value that is not a URL is returned unchanged, and the redaction pass leaves it
     * alone because it has no scheme.
     *
     * @param endpoint endpoint or host, possibly {@code null}
     * @return the host, or an empty string
     */
    public static String hostOnly(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String value = endpoint.trim();
        int scheme = value.indexOf("://");
        String rest = scheme < 0 ? value : value.substring(scheme + 3);
        int end = rest.length();
        for (int index = 0; index < rest.length(); index++) {
            char character = rest.charAt(index);
            if (character == '/' || character == '?' || character == '#') {
                end = index;
                break;
            }
        }
        String host = rest.substring(0, end);
        int credentials = host.indexOf('@');
        if (credentials >= 0) {
            host = host.substring(credentials + 1);
        }
        return host;
    }

    /**
     * A flattened, length-bounded preview of a text.
     *
     * @param text text to describe, possibly {@code null}
     * @return at most {@link #MAXIMUM_PREVIEW_CHARS} characters, or {@code null} for a null input
     */
    public static String preview(String text) {
        if (text == null) {
            return null;
        }
        String single = text.replace('\r', ' ').replace('\n', ' ');
        if (single.length() <= MAXIMUM_PREVIEW_CHARS) {
            return single;
        }
        // Cutting between the halves of a surrogate pair leaves an unpaired one, which the UTF-8
        // encoder turns into '?', so the pair is dropped instead of split.
        int end = MAXIMUM_PREVIEW_CHARS;
        if (Character.isHighSurrogate(single.charAt(end - 1))) {
            end--;
        }
        return single.substring(0, end) + "...";
    }

    /** Queues one already formatted line. Caller holds {@link #lock}. */
    private void appendLineLocked(String line) {
        if (pending.length() + line.length() + 2 > MAXIMUM_PENDING_CHARS) {
            // Support logging must never delay a translation, so a full queue drops and reports the
            // loss on the next batch instead of waiting for the flusher.
            droppedLines++;
            return;
        }
        pending.append(line).append(System.lineSeparator());
        pendingLines++;
    }

    /** Queues text that already carries its own line separators. Caller holds {@link #lock}. */
    private void appendPendingLocked(CharSequence text) {
        if (pending.length() + text.length() > MAXIMUM_PENDING_CHARS) {
            droppedLines++;
            return;
        }
        pending.append(text);
    }

    /** Keeps one startup line for the moment the log is switched on. Caller holds {@link #lock}. */
    private void capturePreludeLocked(CharSequence line) {
        if (prelude.length() + line.length() + 2 > MAXIMUM_PRELUDE_CHARS) {
            return;
        }
        prelude.append(line).append(System.lineSeparator());
    }

    /** Starts the single daemon thread that drains the queue. Caller holds {@link #lock}. */
    private void startFlusherLocked() {
        if (flusher != null) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                drainLoop();
            }
        }, "universal-translator-debug-log");
        thread.setDaemon(true);
        flusher = thread;
        thread.start();
    }

    private void drainLoop() {
        while (true) {
            String batch;
            Path target;
            synchronized (lock) {
                if (!enabled || file == null) {
                    flusher = null;
                    return;
                }
                sealStreamLocked();
                target = file;
                batch = takeBatchLocked();
                if (batch == null) {
                    try {
                        lock.wait(FLUSH_INTERVAL_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        flusher = null;
                        return;
                    }
                }
            }
            // Outside the monitor: the point of the queue is that no caller ever waits here.
            writeBatch(batch, target);
        }
    }

    /**
     * Takes the queue as one batch. Caller holds {@link #lock}.
     *
     * @return the text to write, or {@code null} when there is nothing to write
     */
    private String takeBatchLocked() {
        if (pending.length() == 0 && droppedLines == 0 && droppedCharacters == 0) {
            return null;
        }
        if (file == null || !enabled) {
            pending.setLength(0);
            pendingLines = 0;
            droppedLines = 0;
            droppedCharacters = 0;
            return null;
        }
        if (droppedLines > 0 || droppedCharacters > 0) {
            pending.append(TIMESTAMP.format(LocalDateTime.now()))
                    .append(" log-buffer dropped-lines=").append(droppedLines);
            if (droppedCharacters > 0) {
                pending.append(" dropped-chars=").append(droppedCharacters);
            }
            pending.append(System.lineSeparator());
            droppedLines = 0;
            droppedCharacters = 0;
        }
        String batch = pending.toString();
        pending.setLength(0);
        pendingLines = 0;
        return batch;
    }

    /**
     * Writes one batch and owns the state of the target file.
     *
     * <p>Called without {@link #lock}, so the render thread and the translation workers never wait
     * for the disk; {@link #writeLock} keeps a single writer on the file, which is what the rotation
     * decision depends on.
     */
    private void writeBatch(String batch, Path target) {
        if (target == null || batch == null || batch.isEmpty()) {
            return;
        }
        byte[] bytes = batch.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                if (!directoryReady) {
                    Path directory = target.getParent();
                    if (directory != null) {
                        Files.createDirectories(directory);
                    }
                    directoryReady = true;
                }
            } catch (IOException | RuntimeException unusableDirectory) {
                // A read-only or full configuration directory is not a translation failure.
                recordDroppedCharacters(batch.length());
                return;
            }
            try {
                // One stat for the whole batch: the rotation decision used to cost exists+size per line.
                if (sizeOf(target) + bytes.length > MAXIMUM_BYTES) {
                    Files.move(target, target.resolveSibling(ROTATED_FILE_NAME),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException unrotated) {
                // A log viewer holding the rotated file must not cost the batch: appending past the
                // size bound is a smaller loss than a silent gap in the support log.
            }
            try {
                Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException unwritable) {
                // Named in the next batch instead of vanishing: the queue was already emptied.
                recordDroppedCharacters(batch.length());
            }
        }
    }

    /**
     * Reports a lost batch in the next one.
     *
     * <p>Called from the writer, so this is the only place that takes {@link #lock} after
     * {@link #writeLock}; no path takes them in the other order.
     */
    private void recordDroppedCharacters(int characters) {
        synchronized (lock) {
            droppedCharacters += characters;
        }
    }

    /**
     * @param path file that may not exist yet
     * @return the size, or {@code 0} when it cannot be measured
     */
    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException | RuntimeException missing) {
            return 0L;
        }
    }
}
