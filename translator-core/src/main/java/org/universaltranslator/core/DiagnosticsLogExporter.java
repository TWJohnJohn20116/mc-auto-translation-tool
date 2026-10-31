package org.universaltranslator.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes a deliberately small, secret-free diagnostics report for issue attachments. */
public final class DiagnosticsLogExporter {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    /** Prefix of every report this class writes, shared by the naming and the pruning below. */
    private static final String FILE_PREFIX = "diagnostics-";
    /** Reports kept in the directory; the oldest ones are deleted before a new one is written. */
    private static final int MAXIMUM_RETAINED_EXPORTS = 5;
    /** Suffixes tried by {@link #reserve} before giving up on a free name. */
    private static final int MAXIMUM_NAME_RETRIES = 1000;
    /**
     * Age after which a temporary file of an export that never finished is deleted. An export takes
     * seconds, so a name this old belongs to a process that was killed rather than to one in flight.
     */
    private static final long TEMPORARY_ORPHAN_MILLIS = 60_000L;

    /**
     * Credential-ish names. The suffix of {@code secret} is optional so that provider specific
     * names such as {@code baidu-secret}, {@code youdao-secret} or {@code iflytek-api-secret}
     * are covered too.
     */
    private static final String SECRET_NAMES =
            "(authorization|bearer|api[-_ ]?key|secret[-_ ]?(?:id|key)?"
                    + "|access[-_ ]?key|token|password|credential)";

    /**
     * A run that looks like a credential rather than prose: either long enough to be a key, or
     * shorter but containing a digit. Plain words such as "provided" or "incorrect" never match.
     */
    private static final String TOKEN_RUN =
            "(?:[A-Za-z0-9._~+/=-]{16,}|(?=[A-Za-z0-9._~+/=-]*[0-9])[A-Za-z0-9._~+/=-]{8,})";

    /**
     * The rules below, compiled once.
     *
     * <p>{@code String.replaceAll} compiles its argument on every call, and these run per field of
     * every debug log line as well as per line of every export: one request carries five to six
     * fields, so the debug path alone recompiled the same two unconditional and up to four
     * conditional patterns tens of times per request, on the machine of a player who turned tracing
     * on because the game was already stuttering. Every argument here is a compile-time constant, so
     * one {@link Pattern} per rule is exactly equivalent.
     */
    private static final Pattern ADDRESS = Pattern.compile("(?i)https?://\\S+");
    /**
     * A credential inside a JSON body. Its name is quoted, so the assignments below never reached it,
     * and a provider that echoes its own request back in an error message put the key into the report
     * and into the bundle verbatim.
     */
    private static final Pattern JSON_SECRET =
            Pattern.compile("(?i)\"" + SECRET_NAMES + "\"\\s*:\\s*\"[^\"]*\"");
    /** "name=value" / "name: value" / "name: Bearer value", whatever the shape. */
    private static final Pattern ASSIGNED_SECRET =
            Pattern.compile("(?i)(?<![A-Za-z0-9_])" + SECRET_NAMES + "\\s*[=:]\\s*(?:bearer\\s+)?\\S+");
    /** "Authorization Bearer value" (no separator at all). */
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)(?<![A-Za-z0-9_])bearer\\s+" + TOKEN_RUN);
    /** Space separated shapes such as "API key provided: value" or "key is value". */
    private static final Pattern SPACED_SECRET = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])" + SECRET_NAMES
                    + "[\\s_]+(?:provided|supplied|used|set|is|was|returned|"
                    + "rejected|invalid|incorrect|expired)?[\\s_:]*" + TOKEN_RUN);
    /** A bare OpenAI-style key. */
    private static final Pattern SK_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{8,}");

    /**
     * What both exported artefacts promise about their contents.
     *
     * <p>Kept in one place because the report and the bundle each carried their own sentence about
     * the same material, and the two disagreed about whether translated text can be in them.
     */
    static final String PRIVACY_NOTICE = "Privacy: API keys and full endpoints are excluded. "
            + "Statistics may carry a per-provider identifier. A debug log, when included, may "
            + "contain up to 120 characters of source and translated text per request.";

    private DiagnosticsLogExporter() {
    }

    public static Path export(Path outputDirectory, List<String> diagnosticLines)
            throws IOException {
        if (outputDirectory == null) {
            throw new IOException("Diagnostics directory is unavailable");
        }
        Files.createDirectories(outputDirectory);
        prune(outputDirectory, FILE_PREFIX, MAXIMUM_RETAINED_EXPORTS);
        String timestamp = LocalDateTime.now().format(FILE_TIME);
        Path output = reserve(outputDirectory, FILE_PREFIX + timestamp, ".txt");
        StringBuilder report = new StringBuilder(512);
        report.append("MC Auto Translation Tool - Diagnostics\n");
        report.append("Generated: ").append(LocalDateTime.now()).append('\n');
        report.append(PRIVACY_NOTICE).append("\n\n");
        if (diagnosticLines == null || diagnosticLines.isEmpty()) {
            report.append("Diagnostics unavailable\n");
        } else {
            for (String line : diagnosticLines) {
                report.append(sanitize(line)).append('\n');
            }
        }
        // The statistics travel with the report, so a user on a platform whose diagnostics screen
        // has no statistics page can still send them in. They carry no text, endpoints or keys.
        report.append('\n');
        for (String line : TranslationStats.global().plainLines()) {
            report.append(sanitize(line)).append('\n');
        }
        // The report is written under a temporary name and moved onto the reserved one afterwards, so
        // a process killed mid-write cannot leave a truncated report under the name the notice tells
        // the user to attach. reserve() claimed that name already, so nothing else can take it.
        Path temporary = output.resolveSibling(
                output.getFileName().toString() + "." + UUID.randomUUID() + ".tmp");
        boolean placed = false;
        try {
            // Owner-only before the content exists. The temporary carries the same redacted status
            // text and provider lines for the whole duration of the write, while the default mode
            // of a POSIX profile is one every account on the machine can open.
            createOwnerOnlyTemporary(temporary);
            Files.write(temporary, report.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            moveIntoPlace(temporary, output);
            placed = true;
        } finally {
            deleteQuietly(temporary);
            if (!placed) {
                // The reserved name is released with it: an empty file there is not a report, and it
                // would occupy one of the retained slots carrying nothing.
                deleteQuietly(output);
            }
        }
        // The report carries the per-provider lines of the statistics file and the redacted status
        // text, so it is restricted the same way the bundle and the configuration file are.
        LocalConfigSecurity.restrictToOwner(output);
        return output;
    }

    static String sanitize(String value) {
        return sanitize(value, "[address hidden]", "[hidden]", "[key hidden]");
    }

    /**
     * Shared redaction used by both the exported report and the in-game diagnostics screen. The
     * placeholders are parameters so each caller can keep its own localized wording.
     */
    static String sanitize(
            String value,
            String addressPlaceholder,
            String secretPlaceholder,
            String keyPlaceholder
    ) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        clean = ADDRESS.matcher(clean).replaceAll(Matcher.quoteReplacement(addressPlaceholder));
        clean = JSON_SECRET.matcher(clean)
                .replaceAll("\"$1\":\"" + Matcher.quoteReplacement(secretPlaceholder) + "\"");
        // An explicit assignment to a credential-ish name is always redacted, whatever the shape.
        clean = ASSIGNED_SECRET.matcher(clean)
                .replaceAll("$1=" + Matcher.quoteReplacement(secretPlaceholder));
        clean = BEARER_TOKEN.matcher(clean)
                .replaceAll("bearer=" + Matcher.quoteReplacement(secretPlaceholder));
        clean = SPACED_SECRET.matcher(clean)
                .replaceAll("$1=" + Matcher.quoteReplacement(secretPlaceholder));
        clean = SK_KEY.matcher(clean).replaceAll(Matcher.quoteReplacement(keyPlaceholder));
        return clean;
    }

    /**
     * Reserves a name that belongs to this export alone; shared with the bundle exporter.
     *
     * <p>{@code Files.exists} followed by a truncating write let two exports of the same second pick
     * the same name, so the second one cut the first artefact in half. Creating the file instead
     * makes the choice atomic, and the suffix loop retries a name that was taken. The suffix goes
     * before {@code extension}, so a retried name keeps the shape the pruning below recognises.
     *
     * @param directory directory the file is created in
     * @param baseName name without the extension
     * @param extension extension including the dot
     * @return an existing, empty file whose name belongs to this caller
     * @throws IOException when no name in the retry range was free
     */
    static Path reserve(Path directory, String baseName, String extension) throws IOException {
        for (int suffix = 1; suffix <= MAXIMUM_NAME_RETRIES; suffix++) {
            Path candidate = directory.resolve(
                    suffix == 1 ? baseName + extension : baseName + "-" + suffix + extension);
            try {
                Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE).close();
                return candidate;
            } catch (FileAlreadyExistsException taken) {
                // Another export claimed this name a moment ago; try the next one.
            }
        }
        throw new IOException("Too many exports in " + directory);
    }

    /**
     * Moves a finished temporary file onto the name that was reserved for it.
     *
     * <p>Shared with the bundle exporter, which writes its archive the same way and for the same
     * reason: the reserved name is the one the user is told to attach, so it must only ever hold a
     * complete artefact.
     *
     * @param temporary completed temporary file, unique to this export
     * @param target name {@link #reserve} returned for this export
     * @throws IOException when the move failed
     */
    static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            // A filesystem without atomic rename, such as some network shares. The temporary file is
            // already complete, so a plain replace is close enough to atomic for an artefact.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes a name this class created for one attempt, and says nothing when it cannot. */
    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // The name is unique per attempt, so a leftover is never read or reused.
        }
    }

    /**
     * Creates a temporary file that is owner-only from the moment its content exists.
     *
     * <p>Writing straight into a fresh name applies the default mode of the directory, which on a
     * shared POSIX profile is {@code 0644}: the artefact is then readable by every account for the
     * whole write, exactly while it holds the material the finished file is restricted against. The
     * capability is asked first, because a Windows profile already has a per-user ACL and an
     * unconditional attempt would log one "unsupported" line per export.
     *
     * @param temporary name this class created for one attempt
     * @throws IOException when the file cannot be created
     */
    static void createOwnerOnlyTemporary(Path temporary) throws IOException {
        Files.createFile(temporary);
        if (!supportsOwnerPermissions(temporary)) {
            return;
        }
        LocalConfigSecurity.restrictToOwner(temporary);
    }

    /** Whether owner-only permissions can be applied on {@code target} at all. */
    private static boolean supportsOwnerPermissions(Path target) {
        try {
            return Files.getFileStore(target)
                    .supportsFileAttributeView(PosixFileAttributeView.class);
        } catch (IOException | RuntimeException unsupported) {
            return false;
        }
    }

    /** Whether the artefact holds anything; an unreadable size is not proof that it is empty. */
    private static boolean isNonEmpty(Path candidate) {
        try {
            return Files.size(candidate) > 0L;
        } catch (IOException unreadable) {
            return true;
        }
    }

    /** Whether the name belongs to an export that had not reached its reserved name yet. */
    private static boolean isTemporary(Path candidate) {
        Path name = candidate.getFileName();
        return name != null && name.toString().endsWith(".tmp");
    }

    /** Deletes a temporary file an export left behind, once it cannot still be in flight. */
    private static void deleteStaleTemporary(Path candidate) {
        if (System.currentTimeMillis() - lastModifiedMillis(candidate) < TEMPORARY_ORPHAN_MILLIS) {
            return;
        }
        deleteQuietly(candidate);
    }

    /**
     * Deletes the oldest artefacts of one exporter so repeated exports cannot fill the folder.
     *
     * <p>Each artefact carries up to two megabytes of debug log, and nothing ever deleted them: a
     * hundred exports left two hundred megabytes in a directory that launchers and cloud clients
     * sync. Only names carrying {@code prefix} are touched, one slot is left for the file the caller
     * is about to write, and any failure here is swallowed -- housekeeping must never be the reason a
     * support request cannot be exported.
     *
     * @param directory directory the artefacts are written into
     * @param prefix file name prefix identifying that exporter's artefacts
     * @param keep how many of them to keep, including the one about to be written
     */
    static void prune(Path directory, String prefix, int keep) {
        List<Path> existing = new ArrayList<Path>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(directory, prefix + "*")) {
            for (Path candidate : stream) {
                // A temporary file is an export that never reached its reserved name. It is not an
                // export, so it must not take one of the retained slots, and an orphan is deleted here
                // because nothing else ever looks at that name again.
                if (isTemporary(candidate)) {
                    deleteStaleTemporary(candidate);
                    continue;
                }
                // A zero byte artefact is a name that was reserved and never written into -- an export
                // killed in between. Counting it as one of the kept exports would let it push out an
                // artefact that did work, but leaving it out of that list also left it out of the only
                // loop that ever deletes a name, so the stale sweep below removes it instead.
                if (Files.isRegularFile(candidate) && !isNonEmpty(candidate)) {
                    deleteStaleTemporary(candidate);
                    continue;
                }
                if (Files.isRegularFile(candidate) && isNonEmpty(candidate)) {
                    existing.add(candidate);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return;
        }
        int excess = existing.size() - (keep - 1);
        if (excess <= 0) {
            return;
        }
        Collections.sort(existing, new Comparator<Path>() {
            @Override
            public int compare(Path first, Path second) {
                long left = lastModifiedMillis(first);
                long right = lastModifiedMillis(second);
                return left < right ? -1 : (left > right ? 1 : 0);
            }
        });
        for (int index = 0; index < excess; index++) {
            try {
                Files.deleteIfExists(existing.get(index));
            } catch (IOException ignored) {
                // A read-only directory must not fail the export that called this.
            }
        }
    }

    /** Last modification time in milliseconds, or {@code 0} when it cannot be read. */
    private static long lastModifiedMillis(Path candidate) {
        try {
            return Files.getLastModifiedTime(candidate).toMillis();
        } catch (IOException unreadable) {
            return 0L;
        }
    }
}
