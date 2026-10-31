package org.universaltranslator.core;

import org.universaltranslator.core.provider.OnlineProviderConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
    /** Archives kept in the configuration directory; the oldest ones are deleted before a new one. */
    private static final int MAXIMUM_RETAINED_EXPORTS = 5;

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
        DiagnosticsLogExporter.prune(configDirectory, FILE_PREFIX, MAXIMUM_RETAINED_EXPORTS);
        Path output = DiagnosticsLogExporter.reserve(configDirectory,
                FILE_PREFIX + LocalDateTime.now().format(FILE_TIME), ".zip");
        // Written under a temporary name and moved onto the reserved one afterwards. Writing straight
        // into the final name left a zero byte or a zip without a central directory wherever the
        // process died mid-export; the retention pass below then counted it as one of the five
        // exports and could push out an archive that did work, and the user was told to attach a file
        // that cannot be opened.
        Path temporary = output.resolveSibling(
                output.getFileName().toString() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (OutputStream file = Files.newOutputStream(temporary);
                    ZipOutputStream zip = new ZipOutputStream(file)) {
                put(zip, "environment.txt", environment(platform));
                put(zip, "diagnostics.txt", report(diagnosticLines, configFile));
                putRedactedFile(zip, "config.properties", configFile,
                        "# The configuration is redacted: keys, tokens and endpoints are replaced.");
                putRedactedFile(zip, "debug.log", debugLogFile,
                        "# Debug logging was off, so no log was written.");
            }
            DiagnosticsLogExporter.moveIntoPlace(temporary, output);
        } catch (IOException | RuntimeException failed) {
            // A half-written archive is worse than none: it cannot be opened, it still counts against
            // the retention limit and it hides the bundle that did work. The failure is rethrown, so
            // the caller still reports it. The reserved name is released as well, because a leftover
            // there would occupy one of the retained slots while holding nothing.
            DiagnosticsLogExporter.deleteQuietly(temporary);
            DiagnosticsLogExporter.deleteQuietly(output);
            throw failed;
        }
        // The archive carries the configuration plus a slice of what the player and the provider
        // said, so it is restricted the same way the configuration it was derived from is instead of
        // inheriting the default permissions of a shared user profile.
        LocalConfigSecurity.restrictToOwner(output);
        return output;
    }

    /**
     * Redacts a file line by line straight into an archive entry; a missing file becomes one comment
     * line.
     *
     * <p>Streaming rather than {@code readAllLines} plus a {@code StringBuilder} plus {@code
     * getBytes}: the log is bounded at two megabytes, which those four copies turned into a peak of
     * six to eight megabytes on the thread that handled the button press.
     */
    private static void putRedactedFile(
            ZipOutputStream zip,
            String name,
            Path source,
            String missingComment
    ) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        if (source == null || !Files.isRegularFile(source)) {
            zip.write((missingComment + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(source), tolerantUtf8()), 8192)) {
            String line;
            while ((line = reader.readLine()) != null) {
                zip.write((DebugLog.redact(line) + '\n').getBytes(StandardCharsets.UTF_8));
            }
        }
        zip.closeEntry();
    }

    /**
     * A UTF-8 decoder that replaces bytes it cannot use instead of refusing the whole file.
     *
     * <p>{@code Files.newBufferedReader} reports malformed input, so one truncated byte failed the
     * whole export with an {@code IOException} -- in exactly the situation a bundle is made for: a
     * process killed in the middle of an append, a full disk, or a sync client that cut the file.
     * The redaction and the streaming shape are unaffected; only invalid bytes become {@code ?}.
     */
    private static CharsetDecoder tolerantUtf8() {
        // A fresh decoder per call: a CharsetDecoder keeps state and must not be shared.
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
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
        // The statement describes this archive rather than a hypothetical one, and it is the same
        // string the exported report prints: two sentences about the same material disagreed before.
        content.append(DiagnosticsLogExporter.PRIVACY_NOTICE).append('\n');
        return content.toString();
    }

    private static String report(List<String> diagnosticLines, Path configFile) {
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
        // The effective throughput settings, with a note for any value that had to be clamped. They
        // are derived from the configuration file here rather than passed in by the screen, so every
        // platform's bundle reports them without its diagnostics screen having to know about them.
        for (String line : OnlineProviderConfig.effectiveSettingsLines(configFile)) {
            content.append(DebugLog.redact(line)).append('\n');
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
