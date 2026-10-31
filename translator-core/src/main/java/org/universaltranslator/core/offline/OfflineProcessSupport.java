package org.universaltranslator.core.offline;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.universaltranslator.core.TextPreview;
import org.universaltranslator.core.net.CryptoSupport;

/** Platform-specific process setup and compact diagnostics for the local llama.cpp server. */
public final class OfflineProcessSupport {
    public static final int WINDOWS_MISSING_DEPENDENCY_EXIT = 0xC0000135;
    public static final int WINDOWS_ILLEGAL_INSTRUCTION_EXIT = 0xC000001D;
    private static final int MAX_LOG_BYTES = 16 * 1024;
    private static final int MAX_DETAIL_CHARACTERS = 240;
    /** Copy chunk for the compatibility alias: large enough to be fast, small enough to cancel. */
    private static final int COPY_CHUNK_BYTES = 64 * 1024;
    /** Windows and macOS compare path entries without case; the other targets do not. */
    private static final boolean CASE_INSENSITIVE_PATHS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    /** Suffix of a finished ASCII alias. */
    private static final String ALIAS_SUFFIX = ".gguf";
    /** Suffix of the sidecar that records an alias verification, see {@code ModelVerificationCache}. */
    private static final String ALIAS_SIDECAR_SUFFIX = ".gguf.verified";
    /** Suffix of the scratch file an alias copy is written to before it is moved into place. */
    private static final String ALIAS_PARTIAL_SUFFIX = ".gguf.part";
    /**
     * How long a scratch file must have been untouched before another start may delete it.
     *
     * <p>Two instances of the game share this directory, and the one that is copying right now owns
     * its own {@code .part}. Deleting it made that instance's move fail after it had already written
     * the whole gigabyte, so the sweep only touches a scratch file that clearly has no writer left.
     */
    private static final long PARTIAL_GRACE_MILLIS = 10L * 60L * 1000L;

    private OfflineProcessSupport() {
    }

    /**
     * Cooperative cancellation for the model copy.
     *
     * <p>Copying a 1.12 GB model must stop when the caller's startup attempt was superseded —
     * closing the world, pressing the translation toggle or switching provider — instead of
     * finishing into a directory nobody will read. The offline package cannot depend on the
     * provider's startup token, so the caller passes a predicate.
     */
    public interface CancellationCheck {
        /** @return {@code true} when the work should stop as soon as possible */
        boolean isCancelled();
    }

    private static final CancellationCheck NEVER_CANCELLED = new CancellationCheck() {
        @Override
        public boolean isCancelled() {
            return false;
        }
    };

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
        if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
            normalized = normalized.substring(2);
        }
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
            throws IOException, InterruptedException {
        return prepareModelPathForNativeProcess(model, stableId, NEVER_CANCELLED);
    }

    /**
     * Same as {@link #prepareModelPathForNativeProcess(Path, String)}, with a hook that stops the
     * copy; whether the alias is required is still derived from the platform and the model path.
     *
     * @param cancellation polled between copy chunks, for example {@code () -> !isCurrent(token)}
     * @throws InterruptedException when the copy was cancelled; any partial copy is deleted first
     */
    public static Path prepareModelPathForNativeProcess(
            Path model,
            String stableId,
            CancellationCheck cancellation
    ) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return prepareModelPathForNativeProcess(
                model, stableId, os.contains("win") && !isAsciiPath(model), cancellation);
    }

    /** Visible for dependency-free Windows path regression tests. */
    public static Path prepareModelPathForNativeProcess(
            Path model,
            String stableId,
            boolean requireAsciiAlias
    ) throws IOException, InterruptedException {
        return prepareModelPathForNativeProcess(model, stableId, requireAsciiAlias, NEVER_CANCELLED);
    }

    /**
     * Same as {@link #prepareModelPathForNativeProcess(Path, String, boolean)}, with a hook that
     * stops the copy.
     *
     * @param cancellation polled between copy chunks; {@code null} means "never cancelled"
     * @throws InterruptedException when the copy was cancelled; any partial copy is deleted first
     */
    public static Path prepareModelPathForNativeProcess(
            Path model,
            String stableId,
            boolean requireAsciiAlias,
            CancellationCheck cancellation
    ) throws IOException, InterruptedException {
        CancellationCheck cancelled = cancellation == null ? NEVER_CANCELLED : cancellation;
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
        // Every candidate directory is cleaned before this attempt writes: an alias left by another
        // model level or another engine build is a gigabyte each, no other sweep can see it, and the
        // free-space check below would otherwise refuse to start because of the leftovers.
        for (Path candidate : bases) {
            if (isAsciiPath(candidate)) {
                sweepStaleAliases(candidate.resolve(".universal-translator-native"), aliasName);
            }
        }
        for (Path base : bases) {
            if (!isAsciiPath(base)) {
                continue;
            }
            Path directory = base.resolve(".universal-translator-native");
            Path alias = directory.resolve(aliasName);
            // A stable name, not a name minted per attempt. Every attempt reuses the same file, so
            // a copy that was killed halfway (window closed, task manager, JVM crash) leaves at
            // most one partial file that the next attempt overwrites instead of a fresh
            // multi-hundred-megabyte orphan per interruption.
            Path temporary = directory.resolve(aliasName + ".part");
            try {
                Files.createDirectories(directory);
                if (!isAsciiPath(alias)) {
                    continue;
                }
                requireNotCancelled(cancelled);
                if (isReusableModelAlias(alias, normalizedModel, stableId)) {
                    return alias;
                }
                Files.deleteIfExists(alias);
                ModelVerificationCache.forget(alias);
                deleteAbandonedTemporaries(directory, aliasName);
                Files.deleteIfExists(temporary);
                long sourceSize = Files.size(normalizedModel);
                requireSpaceForCopy(directory, sourceSize);
                boolean linked = false;
                String copyDigest = null;
                try {
                    Files.createLink(temporary, normalizedModel);
                    linked = true;
                } catch (IOException | UnsupportedOperationException linkFailure) {
                    // Cross-volume, non-NTFS and network shares cannot be hard-linked; the copy
                    // computes the digest while writing so verification costs no second read.
                    copyDigest = copyModel(normalizedModel, temporary, sourceSize, cancelled);
                }
                if (Files.size(temporary) != sourceSize) {
                    throw new IOException("Offline model compatibility copy is incomplete");
                }
                if (!verifiesCopy(temporary, normalizedModel, stableId, linked, copyDigest)) {
                    throw new IOException("Offline model compatibility copy failed verification");
                }
                try {
                    Files.move(temporary, alias, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, alias, StandardCopyOption.REPLACE_EXISTING);
                }
                // The conclusion was established by the copy itself, so the next start only has to
                // stat the alias instead of hashing another gigabyte.
                ModelVerificationCache.remember(alias, stableId);
                return alias;
            } catch (InterruptedException cancelledMidway) {
                deleteQuietly(temporary);
                throw cancelledMidway;
            } catch (IOException failure) {
                lastFailure = failure;
                deleteQuietly(temporary);
            } catch (SecurityException failure) {
                lastFailure = new IOException("Windows denied creation of an ASCII model alias",
                        failure);
                deleteQuietly(temporary);
            }
        }
        throw new IOException("Could not create an ASCII-only path for the offline model",
                lastFailure);
    }

    /**
     * Copies the model in bounded chunks while hashing it.
     *
     * @return hex SHA-256 of the bytes written
     * @throws InterruptedException when the copy was cancelled
     */
    private static String copyModel(
            Path source,
            Path target,
            long expectedSize,
            CancellationCheck cancellation
    ) throws IOException, InterruptedException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[COPY_CHUNK_BYTES];
            long copied = 0L;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                requireNotCancelled(cancellation);
                copied += count;
                if (copied > expectedSize) {
                    throw new IOException("Offline model changed while it was being copied");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        return CryptoSupport.hex(digest.digest());
    }

    private static void requireNotCancelled(CancellationCheck cancellation)
            throws InterruptedException {
        if (cancellation.isCancelled()) {
            throw new InterruptedException("Offline model compatibility copy was cancelled");
        }
    }

    /** True when the copy that produced {@code temporary} is the model's verified content. */
    private static boolean verifiesCopy(
            Path temporary,
            Path model,
            String stableId,
            boolean linked,
            String copyDigest
    ) throws IOException {
        if (linked) {
            // A hard link is the model itself: the identity check is conclusive and free.
            return matchesVerifiedModel(temporary, model, stableId);
        }
        if (copyDigest != null) {
            // The digest came out of the copy itself, so verifying it costs no second read. A
            // stable id that is not a digest cannot be compared against, exactly as before.
            return isSha256(stableId) && stableId.equalsIgnoreCase(copyDigest);
        }
        return matchesVerifiedModel(temporary, model, stableId);
    }

    /**
     * Removes temporaries left behind by an interrupted copy.
     *
     * <p>Older builds named the temporary after {@code System.nanoTime()}, so a copy that was cut
     * short left a file nobody could ever match to a retry. The name is stable now, but sweeping the
     * old pattern keeps those orphans from sitting in the directory forever.
     */
    private static void deleteAbandonedTemporaries(Path directory, String aliasName) {
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(directory, aliasName + ".tmp-*")) {
            for (Path entry : entries) {
                deleteQuietly(entry);
            }
        } catch (IOException | RuntimeException unsupported) {
            // The sweep is hygiene only; failing to list a directory must not fail the start.
        }
    }

    /**
     * Removes compatibility aliases left behind by a different model or engine build.
     *
     * <p>An alias is a gigabyte-sized file — a hard link where the volume supports one and a full
     * copy where it does not — but only this attempt's own name was ever swept: a user who switched
     * between the LITE and QUALITY models kept both forever, and the leftover is exactly what makes
     * the next install fail its own free-space check with no file the user can point at.
     *
     * <p>Scoped to one {@code .universal-translator-native} directory, so nothing outside this
     * module's own cache is ever touched. A file another instance is reading cannot be removed on
     * Windows; {@link #deleteQuietly(Path)} leaves those for the next start.
     *
     * @param directory the per-base cache directory holding the aliases
     * @param keepName the alias and sidecars this attempt is about to use
     */
    private static void sweepStaleAliases(Path directory, String keepName) {
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(directory, "model-*.gguf*")) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                // Every suffix of the kept alias stays: ".part" is this attempt's own scratch file
                // and ".verified" is the sidecar that spares the next start a gigabyte of hashing.
                if (name.startsWith(keepName)) {
                    continue;
                }
                // Only a finished alias, its sidecar and a scratch file nobody is writing are this
                // sweep's business. Two instances of the game share the directory, and the one that
                // is copying right now owns its ".part": deleting it made that instance's move fail
                // after it had written the whole gigabyte. An unknown suffix is not a file this
                // module ever created, so it is left alone as well.
                boolean finishedAlias = name.endsWith(ALIAS_SUFFIX)
                        || name.endsWith(ALIAS_SIDECAR_SUFFIX);
                boolean abandonedScratch = (name.endsWith(ALIAS_PARTIAL_SUFFIX)
                        || name.indexOf(ALIAS_SUFFIX + ".tmp-") >= 0)
                        && isOlderThan(entry, PARTIAL_GRACE_MILLIS);
                if (!finishedAlias && !abandonedScratch) {
                    continue;
                }
                deleteQuietly(entry);
                ModelVerificationCache.forget(entry);
            }
        } catch (IOException | RuntimeException unsupported) {
            // The sweep is hygiene only; failing to list a directory must not fail the start.
        }
    }

    /**
     * Whether {@code file} has been left untouched for at least {@code graceMillis}.
     *
     * <p>An unreadable timestamp counts as "still in use": the sweep is hygiene, so leaving one
     * orphan behind is cheaper than deleting a file another instance is writing right now.
     *
     * @param file candidate to judge
     * @param graceMillis quiet period that proves no writer still owns it
     * @return {@code true} when the file is old enough to sweep
     */
    private static boolean isOlderThan(Path file, long graceMillis) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            return System.currentTimeMillis() - modified > graceMillis;
        } catch (IOException | RuntimeException unknown) {
            return false;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // Preserve the useful creation/copy failure.
        }
    }

    /**
     * Refuses a copy that cannot finish instead of filling the volume.
     *
     * <p>A copy that runs out of space fails only after writing everything it could, and on the
     * Android install directory that is the shared cache partition every other app also uses. The
     * cheap check happens before the first byte is written.
     */
    private static void requireSpaceForCopy(Path directory, long requiredBytes) throws IOException {
        // One reserve, one message shape: the downloader and the extractor ask the same class, so a
        // shortage cannot mean three different things depending on which step noticed it.
        DiskSpace.require(directory, requiredBytes, "offline model copy");
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
     * so llama.cpp's automatic device-memory fitting is unnecessary.
     *
     * <p>The compatibility retry deliberately drops the newer fitting/direct-I/O switches. This
     * matters when an already installed engine comes from an older release or launcher cache: an
     * unknown optional switch would otherwise be repeated on every retry and repair attempt.</p>
     */
    public static void appendStableModelLoadingArguments(
            List<String> command,
            boolean conservativeFileAccess
    ) {
        if (conservativeFileAccess) {
            command.add("--no-mmap");
            return;
        }
        command.add("-fit");
        command.add("off");
        command.add("--no-direct-io");
    }

    private static void addPath(List<String> entries, Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        String normalized = directory.toAbsolutePath().normalize().toString();
        for (String existing : entries) {
            if (samePath(normalized, existing)) {
                return;
            }
        }
        entries.add(normalized);
    }

    private static boolean containsPath(String pathList, String expected) {
        for (String entry : pathList.split(java.util.regex.Pattern.quote(
                String.valueOf(File.pathSeparatorChar)))) {
            if (samePath(expected, entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares two PATH entries the way the host filesystem does.
     *
     * <p>These two neighbours used to disagree: one compared case-insensitively and the other
     * exactly, so a directory already listed as {@code C:\Tools} was added again as
     * {@code c:\tools} on every launch. The duplicate is not cosmetic — PATH has a real length
     * limit on Windows, and the existing value is appended behind the new entries, so the tail is
     * what gets truncated.
     */
    private static boolean samePath(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return CASE_INSENSITIVE_PATHS ? left.equalsIgnoreCase(right) : left.equals(right);
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

    /**
     * True when {@code candidate} is the model, or is content that hashed to the pinned digest.
     *
     * <p>The sidecar is consulted first: a copied alias is a gigabyte of bytes that do not change
     * between starts, and re-reading all of them on every engine start is exactly the cost the
     * provider already avoids for the model itself. On a miss the file is hashed once and the
     * conclusion is recorded for the next start.
     */
    private static boolean matchesVerifiedModel(Path candidate, Path model, String stableId)
            throws IOException {
        try {
            if (Files.isSameFile(candidate, model)) {
                return true;
            }
        } catch (SecurityException ignored) {
            // A copied alias can still be verified by its pinned digest below.
        }
        if (!isSha256(stableId)) {
            return false;
        }
        if (ModelVerificationCache.isVerified(candidate, stableId)) {
            return true;
        }
        if (!stableId.equalsIgnoreCase(VerifiedDownloader.sha256(candidate))) {
            return false;
        }
        ModelVerificationCache.remember(candidate, stableId);
        return true;
    }

    private static boolean isSha256(String value) {
        return ModelVerificationCache.isSha256(value);
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
            return TextPreview.oneLine(joined.toString(), MAX_DETAIL_CHARACTERS - 3) + "...";
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
                || lower.contains("unknown") || lower.contains("unrecognized option")
                || lower.contains("exception")
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
        if (exitCode == WINDOWS_ILLEGAL_INSTRUCTION_EXIT) {
            return "离线引擎使用了当前 CPU 不支持的指令（退出码 " + code + "）";
        }
        String detail = logDetail == null ? "" : logDetail.trim();
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown argument") || lower.contains("unknown option")
                || lower.contains("unrecognized option") || lower.contains("invalid option")) {
            return "离线引擎启动参数不兼容（退出码 " + code + "）：" + detail;
        }
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
        // The exit code has to be read before the generic branch: llama.cpp always writes something
        // to its log while loading, so `detail` was never empty and the one failure the user can
        // actually fix (kill another program, drop to a smaller model) never reached them.
        if (exitCode == 9 || exitCode == 137) {
            String hint = "离线引擎被系统终止（退出码 " + code + "），请检查可用内存";
            return detail.isEmpty() ? hint : hint + "：" + detail;
        }
        if (!detail.isEmpty()) {
            return "离线引擎启动失败（退出码 " + code + "）：" + detail;
        }
        return "离线引擎启动失败（退出码 " + code + "），详细信息见 llama-server.log";
    }

    public static String describeProcessStartFailure(IOException failure) {
        String detail = failure == null || failure.getMessage() == null
                ? "" : failure.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied") || lower.contains("access is denied")
                || lower.contains("operation not permitted")) {
            return "离线引擎没有执行权限；请检查游戏目录权限或安全软件拦截";
        }
        if (lower.contains("no such file") || lower.contains("cannot find")
                || lower.contains("not found")) {
            return "离线引擎文件缺失；将自动重新下载安装";
        }
        if (detail.isEmpty()) {
            return "无法启动离线引擎进程";
        }
        if (detail.length() > MAX_DETAIL_CHARACTERS) {
            detail = TextPreview.oneLine(detail, MAX_DETAIL_CHARACTERS - 3) + "...";
        }
        return "无法启动离线引擎进程：" + detail;
    }

    public static String describeStartupTimeout(String logDetail) {
        String detail = logDetail == null ? "" : logDetail.trim();
        if (detail.isEmpty()) {
            return "离线模型在 90 秒内未完成启动，详细信息见 llama-server.log";
        }
        return "离线模型在 90 秒内未完成启动：" + detail;
    }
}
