package org.universaltranslator.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/** Writes a deliberately small, secret-free diagnostics report for issue attachments. */
public final class DiagnosticsLogExporter {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

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

    private DiagnosticsLogExporter() {
    }

    public static Path export(Path outputDirectory, List<String> diagnosticLines)
            throws IOException {
        if (outputDirectory == null) {
            throw new IOException("Diagnostics directory is unavailable");
        }
        Files.createDirectories(outputDirectory);
        String timestamp = LocalDateTime.now().format(FILE_TIME);
        Path output = uniqueFile(outputDirectory, timestamp);
        StringBuilder report = new StringBuilder(512);
        report.append("MC Auto Translation Tool - Diagnostics\n");
        report.append("Generated: ").append(LocalDateTime.now()).append('\n');
        report.append("Privacy: API endpoints, keys, translated text and chat are excluded.\n\n");
        if (diagnosticLines == null || diagnosticLines.isEmpty()) {
            report.append("Diagnostics unavailable\n");
        } else {
            for (String line : diagnosticLines) {
                report.append(sanitize(line)).append('\n');
            }
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
        Path candidate = directory.resolve("diagnostics-" + timestamp + ".txt");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve("diagnostics-" + timestamp + "-" + suffix + ".txt");
            suffix++;
        }
        return candidate;
    }
}
