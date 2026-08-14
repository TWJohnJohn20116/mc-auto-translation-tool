package org.universaltranslator.core.offline;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Platform-specific process setup and compact diagnostics for the local llama.cpp server. */
public final class OfflineProcessSupport {
    public static final int WINDOWS_MISSING_DEPENDENCY_EXIT = 0xC0000135;
    private static final int MAX_LOG_BYTES = 16 * 1024;
    private static final int MAX_DETAIL_CHARACTERS = 240;

    private OfflineProcessSupport() {
    }

    /**
     * The official Windows llama.cpp build uses the MSVC runtime. Minecraft launchers normally
     * bundle those DLLs beside Java, but an explicitly selected Java executable is not necessarily
     * present in PATH. Add its bin directory for the child process without changing the computer.
     */
    public static void configureLibraryPath(ProcessBuilder builder, Path serverDirectory) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return;
        }
        String javaHome = System.getProperty("java.home", "");
        Path javaBin = javaHome.trim().isEmpty() ? null : new File(javaHome, "bin").toPath();
        prependWindowsLibraryPath(builder, serverDirectory, javaBin);
    }

    /** Visible for dependency-free regression tests. */
    public static void prependWindowsLibraryPath(
            ProcessBuilder builder,
            Path serverDirectory,
            Path javaBin
    ) {
        Map<String, String> environment = builder.environment();
        String pathKey = "PATH";
        for (String key : environment.keySet()) {
            if ("PATH".equalsIgnoreCase(key)) {
                pathKey = key;
                break;
            }
        }
        String existing = environment.get(pathKey);
        List<String> entries = new ArrayList<String>();
        addPath(entries, serverDirectory);
        addPath(entries, javaBin);
        if (existing != null && !existing.trim().isEmpty()) {
            entries.add(existing);
        }
        StringBuilder joined = new StringBuilder();
        for (String entry : entries) {
            if (joined.length() > 0) {
                joined.append(File.pathSeparatorChar);
            }
            joined.append(entry);
        }
        environment.put(pathKey, joined.toString());
    }

    /**
     * Keep the pinned CPU server away from optional loading paths that are fragile on some
     * launcher-managed Windows installations. The model and context sizes are already explicit,
     * so llama.cpp's automatic device-memory fitting is unnecessary. The conservative retry also
     * avoids memory mapping for game directories backed by unusual filesystems or security tools.
     */
    public static void appendStableModelLoadingArguments(
            List<String> command,
            boolean conservativeFileAccess
    ) {
        command.add("-fit");
        command.add("off");
        command.add("--no-direct-io");
        if (conservativeFileAccess) {
            command.add("--no-mmap");
        }
    }

    private static void addPath(List<String> entries, Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        String normalized = directory.toAbsolutePath().normalize().toString();
        for (String existing : entries) {
            if (normalized.equalsIgnoreCase(existing)) {
                return;
            }
        }
        entries.add(normalized);
    }

    /** Reads only output written by the current startup attempt. */
    public static String readNewLogTail(Path log, long attemptStartedAtByte) {
        if (log == null || !Files.isRegularFile(log)) {
            return "";
        }
        try {
            long size = Files.size(log);
            long requestedStart = Math.max(0L, attemptStartedAtByte);
            long start = Math.max(requestedStart, size - MAX_LOG_BYTES);
            if (start >= size) {
                return "";
            }
            int length = (int) Math.min((long) MAX_LOG_BYTES, size - start);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            try (SeekableByteChannel channel = Files.newByteChannel(log, StandardOpenOption.READ)) {
                channel.position(start);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // Keep reading until the requested tail is complete or EOF is reached.
                }
            }
            buffer.flip();
            String text = StandardCharsets.UTF_8.decode(buffer).toString();
            return summarizeLog(text);
        } catch (IOException ignored) {
            return "";
        }
    }

    /** Visible for dependency-free regression tests. */
    public static String summarizeLog(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        List<String> specific = new ArrayList<String>();
        List<String> errors = new ArrayList<String>();
        List<String> meaningful = new ArrayList<String>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replaceAll("\\s+", " ").trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("cleaning up before exit")
                    || lower.contains("exiting due to model loading error")) {
                continue;
            }
            meaningful.add(line);
            if (isErrorLine(lower)) {
                addUnique(errors, line);
                if (isSpecificErrorLine(lower)) {
                    addUnique(specific, line);
                }
            }
        }
        List<String> selected = !specific.isEmpty() ? specific
                : (!errors.isEmpty() ? errors : meaningful);
        if (selected.isEmpty()) {
            return "";
        }
        int first = Math.max(0, selected.size() - 2);
        StringBuilder joined = new StringBuilder();
        for (int index = first; index < selected.size(); index++) {
            if (joined.length() > 0) {
                joined.append(" | ");
            }
            joined.append(selected.get(index));
        }
        if (joined.length() > MAX_DETAIL_CHARACTERS) {
            return joined.substring(0, MAX_DETAIL_CHARACTERS - 3) + "...";
        }
        return joined.toString();
    }

    private static boolean isErrorLine(String lower) {
        return lower.contains("error") || lower.contains("failed")
                || lower.contains("failure") || lower.contains("exception")
                || lower.contains("invalid") || lower.contains("unsupported")
                || lower.contains("unknown") || lower.contains("unable")
                || lower.contains("cannot") || lower.contains("out of memory")
                || lower.contains("not enough memory") || lower.contains("access is denied")
                || lower.contains("permission denied");
    }

    private static boolean isSpecificErrorLine(String lower) {
        return lower.contains("failed to read magic") || lower.contains("read error")
                || lower.contains("invalid") || lower.contains("unsupported")
                || lower.contains("unknown") || lower.contains("exception")
                || lower.contains("out of memory") || lower.contains("not enough memory")
                || lower.contains("cannot allocate") || lower.contains("no cpu backend")
                || lower.contains("failed to load cpu backend")
                || lower.contains("access is denied") || lower.contains("permission denied")
                || lower.contains("not a valid win32") || lower.contains("entry point");
    }

    private static void addUnique(List<String> values, String value) {
        if (values.isEmpty() || !values.get(values.size() - 1).equals(value)) {
            values.add(value);
        }
    }

    public static String describeStartupExit(int exitCode, String logDetail) {
        String code = String.format("0x%08X", exitCode);
        if (exitCode == WINDOWS_MISSING_DEPENDENCY_EXIT) {
            return "离线引擎缺少 Windows DLL 或 Visual C++ 运行库（退出码 " + code
                    + "）";
        }
        String detail = logDetail == null ? "" : logDetail.trim();
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("out of memory") || lower.contains("not enough memory")
                || lower.contains("cannot allocate")) {
            return "离线模型加载时内存不足（退出码 " + code + "）：" + detail;
        }
        if (lower.contains("no cpu backend") || lower.contains("failed to load cpu backend")) {
            return "离线引擎 CPU 后端加载失败（退出码 " + code + "）：" + detail;
        }
        if (lower.contains("failed to read magic") || lower.contains("read error")) {
            return "离线模型文件读取失败（退出码 " + code + "）：" + detail;
        }
        if (!detail.isEmpty()) {
            return "离线引擎启动失败（退出码 " + code + "）：" + detail;
        }
        return "离线引擎启动失败（退出码 " + code + "），详细信息见 llama-server.log";
    }
}
