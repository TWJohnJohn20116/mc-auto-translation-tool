package org.universaltranslator.core.offline;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Minimal ZIP and tar.gz extractor with traversal and symlink-target validation. */
public final class SafeArchiveExtractor {
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
        try (ZipInputStream input = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path output = safePath(root, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    copy(input, output, entry.getSize());
                }
                input.closeEntry();
            }
        }
    }

    private static void extractTarGz(Path archive, Path root) throws IOException {
        List<PendingLink> links = new ArrayList<PendingLink>();
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
                int type = header[156] & 0xff;
                Path output = safePath(root, name);
                if (type == '5') {
                    Files.createDirectories(output);
                    skipFully(input, size);
                } else if (type == '2') {
                    String target = tarString(header, 157, 100);
                    validateLinkTarget(root, output, target);
                    links.add(new PendingLink(output, target));
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
            createLinkOrCopy(root, link);
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

    private static void createLinkOrCopy(Path root, PendingLink link) throws IOException {
        Files.createDirectories(link.output.getParent());
        Path target = link.output.getParent().resolve(link.target).normalize();
        if (!target.startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("Archive symlink escaped extraction directory");
        }
        try {
            Files.createSymbolicLink(link.output, Paths.get(link.target));
        } catch (UnsupportedOperationException | IOException exception) {
            if (!Files.isRegularFile(target)) {
                throw new IOException("Could not safely materialize archive symlink", exception);
            }
            Files.copy(target, link.output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateLinkTarget(Path root, Path output, String target) throws IOException {
        if (target.isEmpty() || Paths.get(target).isAbsolute()
                || !output.getParent().resolve(target).normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("Unsafe symlink in offline engine archive");
        }
    }

    private static Path safePath(Path root, String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty() || entryName.indexOf('\0') >= 0) {
            throw new IOException("Invalid archive entry name");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path output = normalizedRoot.resolve(entryName).normalize();
        if (!output.startsWith(normalizedRoot)) {
            throw new IOException("Archive entry escaped extraction directory: " + entryName);
        }
        return output;
    }

    private static void copy(InputStream input, Path output, long declaredSize) throws IOException {
        try (OutputStream target = Files.newOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (declaredSize >= 0L && total > declaredSize) {
                    throw new IOException("ZIP entry exceeded its declared size");
                }
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

        private PendingLink(Path output, String target) {
            this.output = output;
            this.target = target;
        }
    }
}
