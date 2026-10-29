package org.universaltranslator.core.offline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import org.universaltranslator.core.UserAgent;

/** HTTPS downloader with resume support, a pinned size and a mandatory SHA-256 check. */
public final class VerifiedDownloader {
    private static final int MAX_REDIRECTS = 8;
    /** Smallest progress step reported to the caller; the final byte is always reported. */
    private static final long PROGRESS_STEP_BYTES = 1024L * 1024L;
    private static final long MINIMUM_FREE_SPACE_BYTES = 64L * 1024L * 1024L;

    /** Receives verified byte progress without owning any UI or platform code. */
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    private static final ProgressListener NO_PROGRESS = new ProgressListener() {
        @Override
        public void onProgress(long downloadedBytes, long totalBytes) {
            // Compatibility overloads deliberately remain silent.
        }
    };

    private VerifiedDownloader() {
    }

    public static Path download(
            URI source,
            Path destination,
            long expectedSize,
            String expectedSha256
    ) throws IOException {
        return download(Collections.singletonList(source), destination, expectedSize, expectedSha256, NO_PROGRESS);
    }

    /** Tries geographically suitable mirrors in order while sharing one resumable partial file. */
    public static Path download(
            Iterable<URI> sources,
            Path destination,
            long expectedSize,
            String expectedSha256
    ) throws IOException {
        return download(sources, destination, expectedSize, expectedSha256, NO_PROGRESS);
    }

    /** Same verified download with resumable progress for in-game status displays. */
    public static Path download(
            Iterable<URI> sources,
            Path destination,
            long expectedSize,
            String expectedSha256,
            ProgressListener progress
    ) throws IOException {
        if (expectedSize < 1L || expectedSha256 == null || expectedSha256.length() != 64) {
            throw new IllegalArgumentException("A pinned size and SHA-256 are required");
        }
        if (sources == null) {
            throw new IllegalArgumentException("At least one download source is required");
        }
        if (progress == null) {
            throw new IllegalArgumentException("Progress listener is required");
        }
        Files.createDirectories(destination.toAbsolutePath().getParent());
        if (Files.isRegularFile(destination)
                && Files.size(destination) == expectedSize
                && isVerifiedContent(destination, expectedSha256)) {
            progress.onProgress(expectedSize, expectedSize);
            return destination;
        }

        IOException failure = null;
        int attempted = 0;
        for (URI source : sources) {
            attempted++;
            try {
                requireSafeDownloadUri(source);
                return downloadFromSource(
                        source, destination, expectedSize, expectedSha256, progress);
            } catch (IOException error) {
                failure = appendFailure(failure, source, error);
            }
        }
        if (attempted == 0) {
            throw new IllegalArgumentException("At least one download source is required");
        }
        throw failure == null ? new IOException("Every download source failed") : failure;
    }

    private static Path downloadFromSource(
            URI source,
            Path destination,
            long expectedSize,
            String expectedSha256,
            ProgressListener progress
    ) throws IOException {
        Path partial = destination.resolveSibling(destination.getFileName().toString() + ".part");
        long offset = Files.isRegularFile(partial) ? Files.size(partial) : 0L;
        if (offset > expectedSize) {
            Files.delete(partial);
            offset = 0L;
        } else if (offset == expectedSize) {
            if (isVerifiedContent(partial, expectedSha256)) {
                progress.onProgress(expectedSize, expectedSize);
                moveVerifiedPartial(partial, destination);
                ModelVerificationCache.forget(partial);
                ModelVerificationCache.remember(destination, expectedSha256);
                return destination;
            }
            Files.delete(partial);
            offset = 0L;
        }
        // Refuse before the first byte instead of filling the volume and failing at 100%. Peak
        // usage is the partial file plus whatever has to be replaced at the end of the move, so
        // only the missing remainder plus a margin has to fit.
        requireSpaceForDownload(destination, expectedSize - offset);

        HttpURLConnection connection = open(source, offset, 0);
        int status;
        boolean append;
        try {
            status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Download returned HTTP " + status + " for " + source.getHost());
            }
            append = offset > 0L && status == HttpURLConnection.HTTP_PARTIAL;
            if (!append) {
                offset = 0L;
            }
            validateResponse(connection, status, offset, expectedSize, append);
        } catch (IOException failure) {
            // The one release point for every failure above. validateResponse throws with the body
            // unread, and an unread body never goes back to the keep-alive pool, so without this the
            // socket and its descriptor leaked once per mirror for an HTML error page, a mismatched
            // Content-Range or a changed Content-Length.
            connection.disconnect();
            throw failure;
        }
        // The length the file had when this attempt started writing. A server that answered a
        // resume with a full body already truncated the file, so nothing of it is kept.
        long attemptOffset = offset;
        progress.onProgress(offset, expectedSize);

        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
        try (InputStream input = connection.getInputStream();
             OutputStream output = Files.newOutputStream(partial, options)) {
            byte[] buffer = new byte[64 * 1024];
            long total = offset;
            long reported = offset;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                total += count;
                if (total > expectedSize) {
                    throw new IOException("Download exceeded its pinned size");
                }
                output.write(buffer, 0, count);
                // The interface used to hand the throttle to the caller, so a 1.12 GB model
                // produced 17 090 callbacks and every consumer had to re-implement the same gate.
                if (total - reported >= PROGRESS_STEP_BYTES || total == expectedSize) {
                    reported = total;
                    progress.onProgress(total, expectedSize);
                }
            }
        } finally {
            connection.disconnect();
        }

        long actualSize = Files.size(partial);
        if (actualSize != expectedSize) {
            throw new IOException("Download is incomplete (" + actualSize + "/" + expectedSize
                    + " bytes); it will resume automatically next time");
        }
        String actualSha256 = sha256(partial);
        if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
            // Only the bytes this attempt wrote are suspect. Throwing away the whole partial made
            // the next mirror restart a gigabyte from zero, so the failure of one mirror also
            // destroyed the progress of the ones before it.
            discardUnverifiedTail(partial, attemptOffset);
            throw new IOException("Downloaded file failed SHA-256 verification");
        }
        progress.onProgress(expectedSize, expectedSize);
        moveVerifiedPartial(partial, destination);
        ModelVerificationCache.remember(destination, expectedSha256);
        return destination;
    }

    /**
     * Truncates a failed download back to the length it had before the attempt.
     *
     * <p>With no earlier progress there is nothing to keep, so the file is removed; with earlier
     * progress the next mirror only has to fetch the segment this one wrote.
     */
    private static void discardUnverifiedTail(Path partial, long keepBytes) {
        try {
            long size = Files.size(partial);
            if (keepBytes <= 0L || size <= keepBytes) {
                Files.deleteIfExists(partial);
                return;
            }
            try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(
                    partial, StandardOpenOption.WRITE)) {
                channel.truncate(keepBytes);
            }
        } catch (IOException | RuntimeException unusable) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException | RuntimeException ignored) {
                // A leftover partial is retried from its recorded length next time.
            }
        }
    }

    /**
     * Fails before the transfer when the destination volume cannot hold it.
     *
     * @param neededBytes bytes the transfer still has to add
     */
    private static void requireSpaceForDownload(Path destination, long neededBytes) throws IOException {
        long usable;
        try {
            usable = Files.getFileStore(destination.toAbsolutePath().getParent()).getUsableSpace();
        } catch (IOException | RuntimeException unknown) {
            return;
        }
        if (usable < neededBytes + MINIMUM_FREE_SPACE_BYTES) {
            throw new IOException("Not enough free space for the download: " + neededBytes
                    + " bytes needed, " + usable + " available");
        }
    }

    /**
     * True when the file already holds the pinned content.
     *
     * <p>The sidecar records a finished verification, so a restart of an already installed
     * component costs a {@code stat} instead of reading a gigabyte back off the disk.
     */
    private static boolean isVerifiedContent(Path file, String expectedSha256) throws IOException {
        if (ModelVerificationCache.isVerified(file, expectedSha256)) {
            return true;
        }
        if (!expectedSha256.equalsIgnoreCase(sha256(file))) {
            return false;
        }
        ModelVerificationCache.remember(file, expectedSha256);
        return true;
    }

    private static void moveVerifiedPartial(Path partial, Path destination) throws IOException {
        try {
            Files.move(partial, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateResponse(
            HttpURLConnection connection,
            int status,
            long offset,
            long expectedSize,
            boolean append
    ) throws IOException {
        String contentType = connection.getContentType();
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")) {
            throw new IOException("Download source returned an HTML page instead of a file");
        }
        if (append) {
            String range = connection.getHeaderField("Content-Range");
            String expectedPrefix = "bytes " + offset + "-";
            if (range == null || !range.toLowerCase(java.util.Locale.ROOT).startsWith(expectedPrefix)
                    || !range.endsWith("/" + expectedSize)) {
                throw new IOException("Download source returned an invalid resume range");
            }
            return;
        }
        long advertised = connection.getContentLengthLong();
        if (status == HttpURLConnection.HTTP_OK && advertised >= 0L && advertised != expectedSize) {
            throw new IOException("Download source advertised an unexpected file size");
        }
    }

    private static IOException appendFailure(IOException previous, URI source, IOException error) {
        IOException combined = new IOException(
                "Download source " + source.getHost() + " failed: " + safeMessage(error), error);
        if (previous != null) {
            combined.addSuppressed(previous);
        }
        return combined;
    }

    private static String safeMessage(IOException error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static HttpURLConnection open(URI uri, long offset, int redirects) throws IOException {
        if (redirects > MAX_REDIRECTS) {
            throw new IOException("Too many download redirects");
        }
        requireSafeDownloadUri(uri);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", UserAgent.VALUE);
        if (offset > 0L) {
            connection.setRequestProperty("Range", "bytes=" + offset + "-");
        }
        int status;
        try {
            status = connection.getResponseCode();
        } catch (IOException failure) {
            // A connect timeout or a TLS handshake failure used to drop this connection on the floor:
            // the caller only ever sees the exception, so nothing was left to close it.
            connection.disconnect();
            throw failure;
        }
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new IOException("Download redirect did not include a destination");
            }
            return open(uri.resolve(location), offset, redirects + 1);
        }
        return connection;
    }

    private static void requireSafeDownloadUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Offline components must be downloaded over HTTPS");
        }
    }
}
