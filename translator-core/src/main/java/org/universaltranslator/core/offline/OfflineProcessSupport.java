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
            String singleLine = text.replace('\r', ' ').replace('\n', ' ')
                    .replaceAll("\\s+", " ").trim();
            if (singleLine.length() > MAX_DETAIL_CHARACTERS) {
                return "..." + singleLine.substring(singleLine.length() - MAX_DETAIL_CHARACTERS + 3);
            }
            return singleLine;
        } catch (IOException ignored) {
            return "";
        }
    }

    public static String describeStartupExit(int exitCode, String logDetail) {
        String code = String.format("0x%08X", exitCode);
        if (exitCode == WINDOWS_MISSING_DEPENDENCY_EXIT) {
            return "离线引擎缺少 Windows DLL 或 Visual C++ 运行库（退出码 " + code
                    + "）";
        }
        String detail = logDetail == null ? "" : logDetail.trim();
        if (!detail.isEmpty()) {
            return "离线引擎启动失败（退出码 " + code + "）：" + detail;
        }
        return "离线引擎启动失败（退出码 " + code + "），详细信息见 llama-server.log";
    }
}
