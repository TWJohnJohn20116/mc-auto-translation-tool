package org.universaltranslator.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/** Writes a deliberately small, secret-free diagnostics report for issue attachments. */
public final class DiagnosticsLogExporter {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    /** Prefix of every report this class writes, shared by the naming and the pruning below. */
    private static final String FILE_PREFIX = "diagnostics-";
    /** Reports kept in the directory; the oldest ones are deleted before a new one is written. */
    private static final int MAXIMUM_RETAINED_EXPORTS = 5;

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
        pruneOldExports(outputDirectory);
        String timestamp = LocalDateTime.now().format(FILE_TIME);
        Path output = uniqueFile(outputDirectory, timestamp);
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
        Files.write(output, report.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
        clean = clean.replaceAll("(?i)https?://\\S+", Matcher.quoteReplacement(addressPlaceholder));
        // A credential inside a JSON body. Its name is quoted, so the assignments below never reached
        // it, and a provider that echoes its own request back in an error message put the key into
        // the report and into the bundle verbatim.
        clean = clean.replaceAll(
                "(?i)\"" + SECRET_NAMES + "\"\\s*:\\s*\"[^\"]*\"",
                "\"$1\":\"" + Matcher.quoteReplacement(secretPlaceholder) + "\"");
        // "name=value" / "name: value" / "name: Bearer value" / "Authorization Bearer value".
        // An explicit assignment to a credential-ish name is always redacted, whatever the shape.
        clean = clean.replaceAll(
                "(?i)(?<![A-Za-z0-9_])" + SECRET_NAMES
                        + "\\s*[=:]\\s*(?:bearer\\s+)?\\S+",
                "$1=" + Matcher.quoteReplacement(secretPlaceholder));
        // "Authorization Bearer value" (no separator at all).
        clean = clean.replaceAll("(?i)(?<![A-Za-z0-9_])bearer\\s+" + TOKEN_RUN,
                "bearer=" + Matcher.quoteReplacement(secretPlaceholder));
        // Space separated shapes such as "API key provided: value" or "key is value".
        clean = clean.replaceAll(
                "(?i)(?<![A-Za-z0-9_])" + SECRET_NAMES
                        + "[\\s_]+(?:provided|supplied|used|set|is|was|returned|"
                        + "rejected|invalid|incorrect|expired)?[\\s_:]*" + TOKEN_RUN,
                "$1=" + Matcher.quoteReplacement(secretPlaceholder));
        clean = clean.replaceAll("(?i)\\bsk-[a-z0-9_-]{8,}",
                Matcher.quoteReplacement(keyPlaceholder));
        return clean;
    }

    private static Path uniqueFile(Path directory, String timestamp) {
        Path candidate = directory.resolve(FILE_PREFIX + timestamp + ".txt");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(FILE_PREFIX + timestamp + "-" + suffix + ".txt");
            suffix++;
        }
        return candidate;
    }

    /**
     * Deletes the oldest reports of this class so repeated exports cannot fill the folder.
     *
     * <p>Nothing ever deleted them, so a player who exports on every retry leaves a growing pile of
     * reports in the configuration directory, which launchers and cloud clients sync. Only names
     * carrying {@link #FILE_PREFIX} are touched, one slot is left for the report the caller is about
     * to write, and any failure here is swallowed: housekeeping must never fail an export.
     *
     * @param directory directory the reports are written into
     */
    private static void pruneOldExports(Path directory) {
        List<Path> existing = new ArrayList<Path>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(directory, FILE_PREFIX + "*")) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate)) {
                    existing.add(candidate);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return;
        }
        int excess = existing.size() - (MAXIMUM_RETAINED_EXPORTS - 1);
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
