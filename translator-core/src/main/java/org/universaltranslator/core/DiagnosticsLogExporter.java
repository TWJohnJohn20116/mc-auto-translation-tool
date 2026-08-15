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

/** Writes a deliberately small, secret-free diagnostics report for issue attachments. */
public final class DiagnosticsLogExporter {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

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
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        clean = clean.replaceAll("(?i)https?://\\S+", "[address hidden]");
        clean = clean.replaceAll(
                "(?i)(authorization|api[-_ ]?key|secret[-_ ]?(id|key)|access[-_ ]?key|token|password)"
                        + "\\s*[=:]\\s*(bearer\\s+)?\\S+",
                "$1=[hidden]");
        clean = clean.replaceAll("(?i)\\bsk-[a-z0-9_-]{8,}", "[key hidden]");
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
