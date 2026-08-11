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

/** HTTPS downloader with resume support, a pinned size and a mandatory SHA-256 check. */
public final class VerifiedDownloader {
    private static final int MAX_REDIRECTS = 8;

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
                && expectedSha256.equalsIgnoreCase(sha256(destination))) {
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
        }

        HttpURLConnection connection = open(source, offset, 0);
        int status = connection.getResponseCode();
        boolean append = offset > 0L && status == HttpURLConnection.HTTP_PARTIAL;
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Download returned HTTP " + status + " for " + source.getHost());
        }
        if (!append) {
            offset = 0L;
        }
        validateResponse(connection, status, offset, expectedSize, append);
        progress.onProgress(offset, expectedSize);

        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
        try (InputStream input = connection.getInputStream();
             OutputStream output = Files.newOutputStream(partial, options)) {
            byte[] buffer = new byte[64 * 1024];
            long total = offset;
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
                progress.onProgress(total, expectedSize);
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
            Files.deleteIfExists(partial);
            throw new IOException("Downloaded file failed SHA-256 verification");
        }
        progress.onProgress(expectedSize, expectedSize);
        try {
            Files.move(partial, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
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
        connection.setRequestProperty("User-Agent", "MCAutoTranslationTool/1.1");
        if (offset > 0L) {
            connection.setRequestProperty("Range", "bytes=" + offset + "-");
        }
        int status = connection.getResponseCode();
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
