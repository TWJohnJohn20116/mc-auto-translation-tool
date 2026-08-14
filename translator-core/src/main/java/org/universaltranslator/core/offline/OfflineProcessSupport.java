package org.universaltranslator.core.offline;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
        if (os.contains("win")) {
            String javaHome = System.getProperty("java.home", "");
            Path javaBin = javaHome.trim().isEmpty() ? null : new File(javaHome, "bin").toPath();
            prependWindowsLibraryPath(builder, serverDirectory, javaBin);
            return;
        }
        if (OfflineEngineAsset.currentRuntimeIsAndroid()) {
            prependEnvironmentPath(builder, "LD_LIBRARY_PATH", serverDirectory);
        }
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

    /** Keeps the Android executable and its sibling shared libraries together at runtime. */
    public static void prependEnvironmentPath(
            ProcessBuilder builder,
            String key,
            Path directory
    ) {
        if (key == null || key.trim().isEmpty() || directory == null
                || !Files.isDirectory(directory)) {
            return;
        }
        Map<String, String> environment = builder.environment();
        String normalized = directory.toAbsolutePath().normalize().toString();
        String existing = environment.get(key);
        if (existing == null || existing.trim().isEmpty()) {
            environment.put(key, normalized);
        } else if (!containsPath(existing, normalized)) {
            environment.put(key, normalized + File.pathSeparatorChar + existing);
        }
    }

    /**
     * Android launchers commonly keep the Minecraft directory on shared storage, which is mounted
     * no-exec. Install the native server in the launcher's private cache/home instead.
     */
    public static Path androidEngineInstallDirectory(Path storageRoot, String runtimeId)
            throws IOException {
        String safeId = requireAsciiFileName(runtimeId);
        List<Path> candidates = new ArrayList<Path>();
        addCandidate(candidates, System.getProperty("java.io.tmpdir", ""));
        addCandidate(candidates, System.getProperty("user.home", ""));
        addCandidate(candidates, System.getenv("POJAV_NATIVEDIR"));
        addCandidate(candidates, System.getenv("POJAV_HOME"));
        if (storageRoot != null && !isAndroidSharedStorage(storageRoot)) {
            candidates.add(storageRoot.toAbsolutePath().normalize());
        }
        IOException lastFailure = null;
        for (Path candidate : candidates) {
            if (isAndroidSharedStorage(candidate)) {
                continue;
            }
            Path directory = candidate.resolve("universal-translator-native").resolve(safeId);
            try {
                Files.createDirectories(directory);
                if (Files.isDirectory(directory) && Files.isWritable(directory)) {
                    return directory;
                }
            } catch (IOException failure) {
                lastFailure = failure;
            } catch (SecurityException failure) {
                lastFailure = new IOException("Android launcher denied access to " + directory,
                        failure);
            }
        }
        throw new IOException(
                "Android launcher did not provide a private executable cache directory",
                lastFailure);
    }

    /** Visible for dependency-free Android shared-storage regression tests. */
    public static boolean isAndroidSharedStorage(Path path) {
        if (path == null) {
            return false;
        }
        String normalized = path.toAbsolutePath().normalize().toString()
                .replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.equals("/sdcard") || normalized.startsWith("/sdcard/")
                || normalized.equals("/storage/emulated")
                || normalized.startsWith("/storage/emulated/")
                || normalized.equals("/mnt/media_rw")
                || normalized.startsWith("/mnt/media_rw/")
                || normalized.equals("/data/media")
                || normalized.startsWith("/data/media/");
    }

    /**
     * Some Windows native builds receive non-ASCII command arguments through the active code page.
     * Mirror a verified model through a stable ASCII-only path before starting llama.cpp.
     */
    public static Path prepareModelPathForNativeProcess(Path model, String stableId)
            throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return prepareModelPathForNativeProcess(
                model, stableId, os.contains("win") && !isAsciiPath(model));
    }

    /** Visible for dependency-free Windows path regression tests. */
    public static Path prepareModelPathForNativeProcess(
            Path model,
            String stableId,
            boolean requireAsciiAlias
    ) throws IOException {
        if (model == null || !Files.isRegularFile(model)) {
            throw new IOException("Offline model file is missing");
        }
        Path normalizedModel = model.toAbsolutePath().normalize();
        if (!requireAsciiAlias || isAsciiPath(normalizedModel)) {
            return normalizedModel;
        }
        String aliasName = "model-" + requireAsciiFileName(stableId) + ".gguf";
        List<Path> bases = asciiWritableAncestors(normalizedModel.getParent());
        // A Windows account name may itself contain non-ASCII characters, making both the user
        // profile and java.io.tmpdir unsuitable. The standard Public profile is an ASCII fallback.
        addCandidate(bases, System.getenv("PUBLIC"));
        addCandidate(bases, System.getProperty("java.io.tmpdir", ""));
        IOException lastFailure = null;
        for (Path base : bases) {
            if (!isAsciiPath(base)) {
                continue;
            }
            Path directory = base.resolve(".universal-translator-native");
            Path alias = directory.resolve(aliasName);
            Path temporary = directory.resolve(aliasName + ".tmp-"
                    + Long.toHexString(System.nanoTime()));
            try {
                Files.createDirectories(directory);
                if (!isAsciiPath(alias)) {
                    continue;
                }
                if (isReusableModelAlias(alias, normalizedModel, stableId)) {
                    return alias;
                }
                Files.deleteIfExists(alias);
                try {
                    Files.createLink(temporary, normalizedModel);
                } catch (IOException | UnsupportedOperationException linkFailure) {
                    Files.copy(normalizedModel, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.size(temporary) != Files.size(normalizedModel)) {
                    throw new IOException("Offline model compatibility copy is incomplete");
                }
                if (!matchesVerifiedModel(temporary, normalizedModel, stableId)) {
                    throw new IOException("Offline model compatibility copy failed verification");
                }
                try {
                    Files.move(temporary, alias, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, alias, StandardCopyOption.REPLACE_EXISTING);
                }
                return alias;
            } catch (IOException failure) {
                lastFailure = failure;
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the useful creation/copy failure.
                }
            } catch (SecurityException failure) {
                lastFailure = new IOException("Windows denied creation of an ASCII model alias",
                        failure);
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the useful access failure.
                }
            }
        }
        throw new IOException("Could not create an ASCII-only path for the offline model",
                lastFailure);
    }

    public static boolean isAsciiPath(Path path) {
        if (path == null) {
            return true;
        }
        String text = path.toAbsolutePath().normalize().toString();
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value < 32 || value > 126) {
                return false;
            }
        }
        return true;
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

    private static boolean containsPath(String pathList, String expected) {
        for (String entry : pathList.split(java.util.regex.Pattern.quote(
                String.valueOf(File.pathSeparatorChar)))) {
            if (expected.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    private static void addCandidate(List<Path> values, String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        try {
            Path candidate = Paths.get(path).toAbsolutePath().normalize();
            if (!values.contains(candidate)) {
                values.add(candidate);
            }
        } catch (RuntimeException ignored) {
            // A launcher supplied an invalid optional directory; continue with other candidates.
        }
    }

    private static List<Path> asciiWritableAncestors(Path start) {
        List<Path> values = new ArrayList<Path>();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (isAsciiPath(candidate) && Files.isDirectory(candidate)
                    && Files.isWritable(candidate)) {
                values.add(candidate);
            }
        }
        return values;
    }

    private static boolean isReusableModelAlias(Path alias, Path model, String stableId) {
        try {
            return Files.isRegularFile(alias)
                    && Files.size(alias) == Files.size(model)
                    && matchesVerifiedModel(alias, model, stableId);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static boolean matchesVerifiedModel(Path candidate, Path model, String stableId)
            throws IOException {
        try {
            if (Files.isSameFile(candidate, model)) {
                return true;
            }
        } catch (SecurityException ignored) {
            // A copied alias can still be verified by its pinned digest below.
        }
        return isSha256(stableId)
                && stableId.equalsIgnoreCase(VerifiedDownloader.sha256(candidate));
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    private static String requireAsciiFileName(String value) {
        String source = value == null ? "" : value.trim();
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < source.length() && safe.length() < 64; index++) {
            char item = source.charAt(index);
            if ((item >= 'a' && item <= 'z') || (item >= 'A' && item <= 'Z')
                    || (item >= '0' && item <= '9') || item == '-' || item == '_'
                    || item == '.') {
                safe.append(item);
            }
        }
        if (safe.length() == 0 || safe.toString().contains("..")) {
            throw new IllegalArgumentException("Invalid native runtime identifier");
        }
        return safe.toString();
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
                || lower.contains("failed to load model")
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
        if (lower.contains("failed to read magic") || lower.contains("read error")
                || lower.contains("failed to load model")) {
            return "离线模型文件读取失败（退出码 " + code + "）：" + detail;
        }
        if (!detail.isEmpty()) {
            return "离线引擎启动失败（退出码 " + code + "）：" + detail;
        }
        return "离线引擎启动失败（退出码 " + code + "），详细信息见 llama-server.log";
    }
}
