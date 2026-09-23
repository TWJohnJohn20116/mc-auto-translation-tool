package org.universaltranslator.core.offline;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Minimal ZIP and tar.gz extractor with traversal and symlink-target validation. */
public final class SafeArchiveExtractor {
    private static final int MAX_ENTRIES = 10_000;
    private static final long MAX_EXPANDED_BYTES = 1_073_741_824L;
    /** Upper bound on how many links may be chained before a link target is treated as broken. */
    private static final int MAX_LINK_HOPS = 16;

    private SafeArchiveExtractor() {
    }

    public static void extract(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        String name = archive.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".zip")) {
            extractZip(archive, destination);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, destination);
        } else {
            throw new IOException("Unsupported offline engine archive: " + name);
        }
    }

    private static void extractZip(Path archive, Path root) throws IOException {
        ExtractionBudget budget = new ExtractionBudget();
        try (ZipInputStream input = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                budget.addEntry();
                Path output = safePath(root, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    copy(input, output, entry.getSize(), budget);
                }
                input.closeEntry();
            }
        }
    }

    private static void extractTarGz(Path archive, Path root) throws IOException {
        List<PendingLink> links = new ArrayList<PendingLink>();
        ExtractionBudget budget = new ExtractionBudget();
        try (InputStream input = new GZIPInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            byte[] header = new byte[512];
            while (readFullyOrEnd(input, header)) {
                if (allZero(header)) {
                    break;
                }
                String name = tarString(header, 0, 100);
                String prefix = tarString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = tarOctal(header, 124, 12);
                budget.addEntry();
                budget.reserve(size);
                int type = header[156] & 0xff;
                Path output = safePath(root, name);
                if (type == '5') {
                    Files.createDirectories(output);
                    skipFully(input, size);
                } else if (type == '2') {
                    String target = tarString(header, 157, 100);
                    validateLinkTarget(root, output, target);
                    links.add(new PendingLink(output, target, name));
                    skipFully(input, size);
                } else if (type == 0 || type == '0') {
                    Files.createDirectories(output.getParent());
                    copyExact(input, output, size);
                } else {
                    skipFully(input, size);
                }
                long padding = (512L - (size % 512L)) % 512L;
                skipFully(input, padding);
            }
        }
        for (PendingLink link : links) {
            createLinkOrCopy(root, link, linksByOutput(links), budget);
        }
    }

    public static Path findServer(Path root) throws IOException {
        final String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "llama-server.exe" : "llama-server";
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            Path result = files.filter(path -> Files.isRegularFile(path)
                            && executable.equals(path.getFileName().toString()))
                    .findFirst().orElse(null);
            if (result == null) {
                throw new IOException("Offline engine archive did not contain " + executable);
            }
            markExecutable(result);
            return result;
        }
    }

    private static Map<Path, PendingLink> linksByOutput(List<PendingLink> links) {
        Map<Path, PendingLink> index = new HashMap<Path, PendingLink>();
        for (PendingLink link : links) {
            index.put(link.output, link);
        }
        return index;
    }

    private static void createLinkOrCopy(
            Path root, PendingLink link, Map<Path, PendingLink> pending, ExtractionBudget budget)
            throws IOException {
        Files.createDirectories(link.output.getParent());
        Path target = link.output.getParent().resolve(link.target).normalize();
        if (!target.startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("Archive symlink escaped extraction directory");
        }
        try {
            Files.createSymbolicLink(link.output, Paths.get(link.target));
        } catch (UnsupportedOperationException | IOException exception) {
            // Windows commonly denies symlink creation without developer mode or elevated
            // privileges. Official engine archives chain symlinks (for example
            // libggml.dylib -> libggml.0.dylib -> libggml.0.15.1.dylib), so the immediate
            // target may itself be a symlink that cannot be created on this platform.
            // Resolve the chain down to the regular file it ultimately names.
            Path resolved = resolveChainToRegularFile(
                    root, target, link.output, link.linkName, pending);
            if (resolved == null) {
                throw new IOException("Could not safely materialize archive symlink", exception);
            }
            // The copy fallback still expands data and must share the same anti-archive-bomb
            // budget as ordinary entries.
            budget.reserve(Files.size(resolved));
            Files.copy(resolved, link.output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Follows a chain of archive symlinks from {@code start} until a regular file is reached.
     * Links that the archive declared but that could not be created on this platform (because
     * symbolic links are unsupported here) are followed through {@code pending} as well.
     *
     * @return the regular file the chain ultimately names, or {@code null} when the chain is
     *         broken, cyclic, too long, or is not a symlink chain at all (for example a hard
     *         link into a directory).
     */
    private static Path resolveChainToRegularFile(
            Path root, Path start, Path linkOutput, String linkName, Map<Path, PendingLink> pending)
            throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path current = start;
        Set<Path> visited = new HashSet<Path>();
        for (int hop = 0; hop < MAX_LINK_HOPS; hop++) {
            if (!current.startsWith(normalizedRoot)) {
                throw new IOException("Archive symlink escaped extraction directory");
            }
            if (Files.isRegularFile(current)) {
                return current;
            }
            if (!visited.add(current)) {
                return null;
            }
            Path linkTarget = null;
            if (Files.isSymbolicLink(current)) {
                linkTarget = Files.readSymbolicLink(current);
            } else {
                PendingLink deferred = pending.get(current);
                if (deferred != null) {
                    linkTarget = Paths.get(deferred.target);
                }
            }
            if (linkTarget == null) {
                return null;
            }
            Path next = linkTarget.isAbsolute()
                    ? linkTarget.normalize()
                    : current.getParent().resolve(linkTarget).normalize();
            if (!next.startsWith(normalizedRoot)) {
                throw new IOException("Archive symlink escaped extraction directory");
            }
            current = next;
        }
        throw new IOException("Archive symlink chain is too deep to resolve: " + linkName);
    }

    private static void validateLinkTarget(Path root, Path output, String target) throws IOException {
        if (target.isEmpty() || target.indexOf('\0') >= 0) {
            throw new IOException("Unsafe symlink in offline engine archive");
        }
        Path resolved;
        try {
            Path targetPath = Paths.get(target);
            if (targetPath.isAbsolute()) {
                throw new IOException("Unsafe symlink in offline engine archive");
            }
            resolved = output.getParent().resolve(targetPath).normalize();
        } catch (InvalidPathException exception) {
            throw new IOException("Unsafe symlink target in offline engine archive", exception);
        }
        if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("Unsafe symlink in offline engine archive");
        }
    }

    private static Path safePath(Path root, String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty() || entryName.indexOf('\0') >= 0
                || containsWindowsIllegalCharacters(entryName)) {
            throw new IOException("Invalid archive entry name");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path output;
        try {
            output = normalizedRoot.resolve(entryName).normalize();
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid archive entry name: " + entryName, exception);
        }
        if (!output.startsWith(normalizedRoot)) {
            throw new IOException("Archive entry escaped extraction directory: " + entryName);
        }
        return output;
    }

    /**
     * Rejects names Windows cannot represent so extraction behaviour is consistent on every
     * platform instead of surfacing as a platform-specific {@link InvalidPathException}.
     */
    private static boolean containsWindowsIllegalCharacters(String entryName) {
        for (int index = 0; index < entryName.length(); index++) {
            char character = entryName.charAt(index);
            if (character < 0x20 || character == '<' || character == '>' || character == ':'
                    || character == '"' || character == '|' || character == '?' || character == '*') {
                return true;
            }
        }
        return false;
    }

    private static void copy(
            InputStream input, Path output, long declaredSize, ExtractionBudget budget)
            throws IOException {
        try (OutputStream target = Files.newOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (declaredSize >= 0L && total > declaredSize) {
                    throw new IOException("ZIP entry exceeded its declared size");
                }
                budget.consume(count);
                target.write(buffer, 0, count);
            }
        }
    }

    private static void copyExact(InputStream input, Path output, long size) throws IOException {
        try (OutputStream target = Files.newOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = size;
            while (remaining > 0L) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new IOException("Truncated tar archive");
                }
                target.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private static boolean readFullyOrEnd(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) {
                if (offset == 0) {
                    return false;
                }
                throw new IOException("Truncated tar header");
            }
            offset += count;
        }
        return true;
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new IOException("Truncated archive");
            }
            remaining--;
        }
    }

    private static String tarString(byte[] value, int offset, int length) {
        int end = offset;
        while (end < offset + length && value[end] != 0) {
            end++;
        }
        return new String(value, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long tarOctal(byte[] value, int offset, int length) throws IOException {
        String text = tarString(value, offset, length).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid tar entry size", exception);
        }
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    private static void markExecutable(Path file) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            permissions = EnumSet.copyOf(permissions);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (Exception ignored) {
            file.toFile().setExecutable(true, true);
        }
    }

    private static final class PendingLink {
        private final Path output;
        private final String target;
        private final String linkName;

        private PendingLink(Path output, String target, String linkName) {
            this.output = output;
            this.target = target;
            this.linkName = linkName;
        }
    }

    private static final class ExtractionBudget {
        private int entries;
        private long expandedBytes;

        private void addEntry() throws IOException {
            entries++;
            if (entries > MAX_ENTRIES) {
                throw new IOException("Archive contains too many entries");
            }
        }

        private void reserve(long bytes) throws IOException {
            if (bytes < 0L || bytes > MAX_EXPANDED_BYTES - expandedBytes) {
                throw new IOException("Archive exceeds the expanded size limit");
            }
            expandedBytes += bytes;
        }

        private void consume(long bytes) throws IOException {
            reserve(bytes);
        }
    }
}
