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
 * mode cannot change behaviour while it is disabled. When it is on the writes are synchronous and
 * best effort: an unwritable file is ignored rather than failing a translation.
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

    private static final DebugLog INSTANCE = new DebugLog();

    private final Object lock = new Object();
    private volatile Path file;
    private volatile boolean enabled;

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
     * header line, which makes it obvious in the file when tracing started.
     *
     * @param logFile file to append to; {@code null} leaves the log disabled
     * @param debugEnabled whether the setting is on
     */
    public void configure(Path logFile, boolean debugEnabled) {
        synchronized (lock) {
            boolean wasEnabled = enabled && file != null;
            file = logFile;
            enabled = debugEnabled && logFile != null;
            if (enabled && !wasEnabled) {
                appendLocked(TIMESTAMP.format(LocalDateTime.now())
                        + " session debug logging enabled");
            }
        }
    }

    /** Whether entries are being written. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Records one event. {@code fields} is a sequence of {@code name, value} pairs. */
    public void log(String event, String... fields) {
        if (!enabled || file == null) {
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
            appendLocked(line.toString());
        }
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

    /** One event arrived from a streamed response. */
    public void logStreamEvent(String provider, String event, int characters) {
        if (!enabled) {
            return;
        }
        log("stream", "provider", provider, "event", event,
                "characters", Integer.toString(characters));
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
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1[key hidden]");
        redacted = NAMED_SECRET_SPACED.matcher(redacted).replaceAll("$1[key hidden]");
        return LONG_BASE64.matcher(redacted).replaceAll("[key hidden]");
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
        return single.length() <= MAXIMUM_PREVIEW_CHARS
                ? single
                : single.substring(0, MAXIMUM_PREVIEW_CHARS) + "...";
    }

    private void appendLocked(String line) {
        Path target = file;
        if (target == null) {
            return;
        }
        try {
            Path directory = target.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            if (Files.exists(target) && Files.size(target) >= MAXIMUM_BYTES) {
                Files.move(target, target.resolveSibling(ROTATED_FILE_NAME),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(target, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // A read-only or full configuration directory is not a translation failure.
        }
    }
}
