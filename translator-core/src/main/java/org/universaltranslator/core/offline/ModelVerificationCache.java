package org.universaltranslator.core.offline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Sidecar that records "this exact file state was hashed to this digest".
 *
 * <p>A model is 0.49-1.12 GB. Verifying one means reading all of it, which costs 2-5 s on an SSD,
 * ~37 s over USB 2.0 and a minute or more over SMB, and every offline engine start used to pay it
 * for the compatibility alias because the alias lived outside the provider's own shortcut. The
 * conclusion is cheap to keep: it only stops being true when the bytes change, and the file state
 * that identifies the bytes is its size, its modification time and its identity on the volume.
 *
 * <p>Size and timestamp alone are not enough. A file that is deleted and rewritten with the same
 * length inside the timestamp's resolution would look unchanged and its digest would be accepted
 * for content that never produced it, so the platform file key (inode number, or volume plus file
 * index on NTFS) participates in the comparison as well. Where the platform cannot report one the
 * marker records {@code -} and the cache simply never matches, which falls back to hashing.
 *
 * <p>Every method is best effort: a read-only install directory, a malformed or truncated marker
 * and a platform that cannot report attributes all mean "no cache", never a failure.
 */
public final class ModelVerificationCache {
    /** Suffix of the sidecar, written next to the file it describes. */
    private static final String SUFFIX = ".verified";
    private static final String HEADER = "universal-translator model verification v2";
    private static final int MARKER_LINES = 5;
    private static final long MAX_MARKER_BYTES = 4096L;

    private ModelVerificationCache() {
    }

    /**
     * True when the sidecar proves that {@code file}'s current state hashes to {@code expectedSha256}.
     *
     * @param file file to check, never {@code null}
     * @param expectedSha256 pinned lowercase or uppercase hex digest
     */
    public static boolean isVerified(Path file, String expectedSha256) {
        if (file == null || !isSha256(expectedSha256)) {
            return false;
        }
        List<String> lines = readMarker(file);
        if (lines == null) {
            return false;
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
        return HEADER.equals(lines.get(0).trim())
                && Long.toString(attributes.size()).equals(lines.get(1).trim())
                && Long.toString(timestampNanos(attributes)).equals(lines.get(2).trim())
                && identity(attributes).equals(lines.get(3).trim())
                && expectedSha256.equalsIgnoreCase(lines.get(4).trim());
    }

    /**
     * Modification time in nanoseconds.
     *
     * <p>Nanoseconds rather than milliseconds because the identity check alone is not enough on a
     * filesystem that reuses an inode the moment it is freed: a file that is deleted and rewritten
     * with the same length can land on the same inode within the same millisecond. The finer
     * timestamp separates the two, and an overflow — impossible for any real file — reports a value
     * no marker can hold, which falls back to hashing.
     */
    private static long timestampNanos(BasicFileAttributes attributes) {
        try {
            return attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS);
        } catch (ArithmeticException overflow) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Records a digest that is already established, either by hashing the file or by writing it.
     *
     * <p>The state is read after the caller finished writing, so the marker describes the file that
     * is on disk now. Nothing is recorded for a non-digest value, and an unwritable directory just
     * leaves the cache empty.
     */
    public static void remember(Path file, String sha256) {
        if (file == null || !isSha256(sha256)) {
            return;
        }
        Path marker = marker(file);
        Path temporary = marker.resolveSibling(marker.getFileName().toString() + ".tmp");
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            StringBuilder content = new StringBuilder(192);
            content.append(HEADER).append('\n');
            content.append(attributes.size()).append('\n');
            content.append(timestampNanos(attributes)).append('\n');
            content.append(identity(attributes)).append('\n');
            content.append(sha256.toLowerCase(Locale.ROOT)).append('\n');
            Files.write(temporary, content.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, marker,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException unwritable) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException ignored) {
                // A leftover temporary file is harmless; the marker is only a cache.
            }
        }
    }

    /** Discards the record for a file that was replaced, so no stale conclusion survives it. */
    public static void forget(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(marker(file));
        } catch (IOException | RuntimeException ignored) {
            // The next verification still hashes the file; the stale marker can never match it.
        }
    }

    /** Sidecar path for a file, deliberately next to it so deleting the file drops the record. */
    private static Path marker(Path file) {
        return file.resolveSibling(file.getFileName().toString() + SUFFIX);
    }

    private static List<String> readMarker(Path file) {
        try {
            Path marker = marker(file);
            if (!Files.isRegularFile(marker) || Files.size(marker) > MAX_MARKER_BYTES) {
                return null;
            }
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            return lines.size() == MARKER_LINES ? lines : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Identity of the file on its volume.
     *
     * <p>Unix reports device and inode, NTFS reports volume serial and file index; both change when
     * a file is recreated. A platform that reports nothing yields {@code -}, which no marker written
     * on a platform that does report one can equal.
     */
    private static String identity(BasicFileAttributes attributes) {
        Object key = attributes.fileKey();
        if (key == null) {
            return "-";
        }
        String text = key.toString();
        StringBuilder safe = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            safe.append(character < 0x20 ? '_' : character);
        }
        return safe.toString();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }
}
