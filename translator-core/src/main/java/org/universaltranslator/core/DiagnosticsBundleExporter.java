package org.universaltranslator.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs everything a support request needs into one archive inside the configuration directory.
 *
 * <p>The bundle holds the diagnostics report, the debug log, the configuration and the environment,
 * and every one of them is passed through the same redaction the exported report uses, so no API
 * key, endpoint or credential header can leave the machine inside it. The debug log and the
 * configuration are copied through the redaction rather than moved, so exporting never alters what
 * the game is running with.
 */
public final class DiagnosticsBundleExporter {
    /** Prefix of the archive written into the configuration directory. */
    public static final String FILE_PREFIX = "universal-translator-diagnostics-";
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private DiagnosticsBundleExporter() {
    }

    /**
     * Writes one archive and returns its path.
     *
     * @param configDirectory directory the archive is created in, normally the game's config folder
     * @param configFile the platform configuration, redacted before it is stored; may be {@code null}
     * @param debugLogFile the debug log, redacted before it is stored; may be {@code null}
     * @param platform free-form platform name, for example {@code fabric-1.21.x}
     * @param diagnosticLines the lines the diagnostics screen shows; may be {@code null}
     * @return the archive that was created
     * @throws IOException when the directory or the archive cannot be written
     */
    public static Path export(
            Path configDirectory,
            Path configFile,
            Path debugLogFile,
            String platform,
            List<String> diagnosticLines
    ) throws IOException {
        if (configDirectory == null) {
            throw new IOException("Diagnostics directory is unavailable");
        }
        Files.createDirectories(configDirectory);
        Path output = uniqueFile(configDirectory,
                FILE_PREFIX + LocalDateTime.now().format(FILE_TIME) + ".zip");
        try (OutputStream file = Files.newOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            put(zip, "environment.txt", environment(platform));
            put(zip, "diagnostics.txt", report(diagnosticLines));
            put(zip, "config.properties", redactedFile(configFile,
                    "# The configuration is redacted: keys, tokens and endpoints are replaced."));
            put(zip, "debug.log", redactedFile(debugLogFile,
                    "# Debug logging was off, so no log was written."));
        }
        return output;
    }

    /** Redacts a file line by line; a missing file becomes a single explanatory line. */
    static String redactedFile(Path source, String missingComment) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            return missingComment + System.lineSeparator();
        }
        StringBuilder content = new StringBuilder(2048);
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            content.append(DebugLog.redact(line)).append('\n');
        }
        return content.toString();
    }

    private static String environment(String platform) {
        StringBuilder content = new StringBuilder(256);
        content.append("MC Auto Translation Tool - Diagnostics bundle\n");
        content.append("Generated: ").append(LocalDateTime.now()).append('\n');
        content.append("Mod version: ").append(UserAgent.VALUE).append('\n');
        content.append("Platform: ").append(platform == null ? "unknown" : platform).append('\n');
        content.append("Java: ").append(System.getProperty("java.version", "unknown"))
                .append(" (").append(System.getProperty("java.vendor", "unknown")).append(")\n");
        content.append("OS: ").append(System.getProperty("os.name", "unknown"))
                .append(' ').append(System.getProperty("os.version", "unknown"))
                .append(' ').append(System.getProperty("os.arch", "unknown")).append('\n');
        content.append("Privacy: API keys, endpoints and translated text are excluded.\n");
        return content.toString();
    }

    private static String report(List<String> diagnosticLines) {
        StringBuilder content = new StringBuilder(1024);
        content.append("MC Auto Translation Tool - Diagnostics\n");
        if (diagnosticLines == null || diagnosticLines.isEmpty()) {
            content.append("Diagnostics unavailable\n");
        } else {
            for (String line : diagnosticLines) {
                content.append(DebugLog.redact(line)).append('\n');
            }
        }
        content.append('\n');
        for (String line : TranslationStats.global().plainLines()) {
            content.append(DebugLog.redact(line)).append('\n');
        }
        return content.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static Path uniqueFile(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(fileName.replace(".zip", "-" + suffix + ".zip"));
            suffix++;
        }
        return candidate;
    }

    /** The entry names the archive always contains, for callers that want to verify a bundle. */
    public static List<String> entryNames() {
        List<String> names = new ArrayList<String>(4);
        names.add("environment.txt");
        names.add("diagnostics.txt");
        names.add("config.properties");
        names.add("debug.log");
        return Collections.unmodifiableList(names);
    }
}
