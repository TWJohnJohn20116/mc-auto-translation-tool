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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import org.universaltranslator.core.UserAgent;
import org.universaltranslator.core.net.CryptoSupport;

/** HTTPS downloader with resume support, a pinned size and a mandatory SHA-256 check. */
public final class VerifiedDownloader {
    private static final int MAX_REDIRECTS = 8;
    /** Smallest progress step reported to the caller; the final byte is always reported. */
    private static final long PROGRESS_STEP_BYTES = 1024L * 1024L;
    /** Window in which a mirror has to deliver {@link #MINIMUM_WINDOW_BYTES}, or it is stalled. */
    private static final long MINIMUM_THROUGHPUT_WINDOW_NANOS = 60L * 1000L * 1000L;
    /**
     * Fewest bytes one window above must deliver.
     *
     * <p>A read timeout only bounds the silence between two reads, so a mirror that sends a few
     * kilobytes just inside it keeps this thread for as long as it likes: at one kilobyte per
     * twenty-nine seconds a 1.12 GB model would take a millennium. Below this rate the transfer is
     * not downloading, and the partial file resumes next time without losing a byte.
     */
    private static final long MINIMUM_WINDOW_BYTES = 256L * 1024L;

    /** Never cancels, so the overloads without a cancellation hook keep their old behaviour. */
    private static final OfflineProcessSupport.CancellationCheck NEVER_CANCELLED =
            new OfflineProcessSupport.CancellationCheck() {
                @Override
                public boolean isCancelled() {
                    return false;
                }
            };

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
        try {
            return download(sources, destination, expectedSize, expectedSha256, progress,
                    NEVER_CANCELLED);
        } catch (InterruptedException unreachable) {
            // Only the overload that takes a caller supplied predicate can cancel, and this one
            // passes a predicate that is never true.
            Thread.currentThread().interrupt();
            throw new IOException("Download was interrupted", unreachable);
        }
    }

    /**
     * Same verified download that also stops when the caller's startup attempt was superseded.
     *
     * <p>A model transfer is the longest lived request in the offline package and used to have no
     * way out: closing the world or switching provider left it writing into a directory nobody
     * would read. The partial file is kept, so the next attempt resumes instead of starting over.
     *
     * @param cancellation polled after every chunk, for example {@code () -> !isCurrent(token)}
     * @throws IOException when every source failed, or the transfer failed its pinned size or digest
     * @throws InterruptedException when the caller cancelled; the partial file is left in place
     */
    public static Path download(
            Iterable<URI> sources,
            Path destination,
            long expectedSize,
            String expectedSha256,
            ProgressListener progress,
            OfflineProcessSupport.CancellationCheck cancellation
    ) throws IOException, InterruptedException {
        OfflineProcessSupport.CancellationCheck cancelled =
                cancellation == null ? NEVER_CANCELLED : cancellation;
        if (expectedSize < 1L) {
            throw new IllegalArgumentException("A pinned size and SHA-256 are required");
        }
        if (!ModelVerificationCache.isSha256(expectedSha256)) {
            // A 64-character value that is not hex (a display form with dashes, a full-width
            // character copied from a page) used to pass this check and only surfaced as a hash
            // mismatch after a gigabyte had been transferred.
            throw new IllegalArgumentException("A pinned size and a hex SHA-256 are required");
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
                        source, destination, expectedSize, expectedSha256, progress, cancelled);
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
            ProgressListener progress,
            OfflineProcessSupport.CancellationCheck cancellation
    ) throws IOException, InterruptedException {
        Path partial = destination.resolveSibling(destination.getFileName().toString() + ".part");
        // Two instances of the game share one models directory. Without a claim the second one
        // opened the same partial file, cut the first one's transfer short and then deleted it over
        // the digest mismatch, so both restarted from the first byte for as long as both were open.
        // The claim covers this whole method: the resume length, the discard and the final move are
        // all decisions about the bytes of this one file.
        java.nio.channels.FileChannel claim = claimPartial(partial, destination);
        try {
            return transferFromSource(source, destination, partial, claim, expectedSize,
                    expectedSha256, progress, cancellation);
        } finally {
            releaseClaim(claim);
        }
    }

    private static Path transferFromSource(
            URI source,
            Path destination,
            Path partial,
            java.nio.channels.FileChannel claim,
            long expectedSize,
            String expectedSha256,
            ProgressListener progress,
            OfflineProcessSupport.CancellationCheck cancellation
    ) throws IOException, InterruptedException {
        // Read only once the claim is held: a length read before it belonged to a file another
        // instance was still writing.
        long offset = Files.isRegularFile(partial) ? Files.size(partial) : 0L;
        if (offset > expectedSize) {
            resetPartial(claim, partial);
            offset = 0L;
        } else if (offset == expectedSize) {
            if (isVerifiedContent(partial, expectedSha256)) {
                progress.onProgress(expectedSize, expectedSize);
                moveVerifiedPartial(partial, destination);
                ModelVerificationCache.forget(partial);
                ModelVerificationCache.remember(destination, expectedSha256);
                return destination;
            }
            resetPartial(claim, partial);
            offset = 0L;
        }
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
            // Refuse before the first byte instead of filling the volume and failing at 100%. Peak
            // usage is the partial file plus whatever has to be replaced at the end of the move, so
            // only the missing remainder plus a margin has to fit. How much is actually missing is
            // only known here: a mirror that ignores the Range header answers 200 and the file is
            // rewritten from its first byte, so asking with the resume offset understated the need
            // by that whole prefix and the transfer then ran out of disk halfway.
            requireSpaceForDownload(destination, expectedSize - offset);
        } catch (IOException failure) {
            // The one release point for every failure above. validateResponse throws with the body
            // unread, and an unread body never goes back to the keep-alive pool, so without this the
            // socket and its descriptor leaked once per mirror for an HTML error page, a mismatched
            // Content-Range or a changed Content-Length.
            connection.disconnect();
            throw failure;
        }
        progress.onProgress(offset, expectedSize);

        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
        // A transfer that starts at byte zero writes every byte of this file through the buffer
        // below exactly once, so hashing them here costs nothing instead of reading the finished
        // 1.12 GB off the disk again. An appended transfer cannot reuse that: the digest of the
        // prefix is not known in this process, and a wrong one would be worse than a second read.
        MessageDigest digestWhileWriting = null;
        if (!append) {
            try {
                digestWhileWriting = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
        try (InputStream input = connection.getInputStream();
             OutputStream output = Files.newOutputStream(partial, options)) {
            byte[] buffer = new byte[64 * 1024];
            long total = offset;
            long reported = offset;
            long windowStartedNanos = System.nanoTime();
            long windowBytes = 0L;
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
                if (digestWhileWriting != null) {
                    digestWhileWriting.update(buffer, 0, count);
                }
                windowBytes += count;
                long now = System.nanoTime();
                // A read timeout bounds one read, never the transfer: a mirror sending a few
                // kilobytes just inside it keeps this thread forever. The bytes one window
                // actually delivered is the bound that ends a trickle.
                if (now - windowStartedNanos > MINIMUM_THROUGHPUT_WINDOW_NANOS) {
                    if (windowBytes < MINIMUM_WINDOW_BYTES) {
                        throw new IOException("Download stalled at " + total + "/" + expectedSize
                                + " bytes; it will resume automatically next time");
                    }
                    windowStartedNanos = now;
                    windowBytes = 0L;
                }
                if (cancellation.isCancelled()) {
                    throw new InterruptedException("Offline model download was cancelled");
                }
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
        String actualSha256 = digestWhileWriting != null
                ? CryptoSupport.hex(digestWhileWriting.digest())
                : sha256(partial);
        if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
            // Nothing in this file is known to be right: the resume offset is only the length of
            // bytes no verification ever covered, so keeping the part written before this attempt
            // replayed the same mismatch forever and one broken prefix made the asset uninstallable.
            // A count of past mismatches is not evidence about these bytes either — the prefix kept
            // there contained the corrupted range that caused them — so the whole partial goes and
            // the next attempt starts from the first byte.
            discardUnverifiedTail(partial, 0L);
            throw new IOException("Downloaded file failed SHA-256 verification; "
                    + partial.getFileName()
                    + " was discarded, so the next attempt starts from the first byte");
        }
        progress.onProgress(expectedSize, expectedSize);
        moveVerifiedPartial(partial, destination);
        // The partial is gone, so a record describing it is a stale conclusion about a file that
        // no longer exists. This is the same cleanup the complete-partial branch above performs;
        // the two success exits are the only places that own the sidecar.
        ModelVerificationCache.forget(partial);
        ModelVerificationCache.remember(destination, expectedSha256);
        return destination;
    }

    /**
     * Takes exclusive ownership of the shared partial file before anything reads or writes it.
     *
     * <p>A leftover {@code .part} resumes from {@code Files.size(partial)}, so two downloads into
     * the same path are two writers of one file, not two independent transfers: whichever of them
     * opened it second truncated what the other had already written. The lock hides inside the
     * partial file itself, so no new file has to be swept, and it disappears with the file on both
     * of the exits that remove it.
     *
     * @return the channel that holds the lock, or {@code null} when this volume cannot lock at all
     * @throws IOException when another instance already owns the partial file
     */
    private static java.nio.channels.FileChannel claimPartial(Path partial, Path destination)
            throws IOException {
        java.nio.channels.FileChannel channel;
        try {
            Files.createDirectories(partial.toAbsolutePath().getParent());
            channel = java.nio.channels.FileChannel.open(
                    partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException cannotOpen) {
            return null;
        }
        java.nio.channels.FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException claimedHere) {
            // Same process, other download: the file is being written by us already.
            closeQuietly(channel);
            throw conflictPartial(destination);
        } catch (IOException | RuntimeException noLocks) {
            // Some SMB and exFAT volumes have no file locks. Keeping the previous behaviour is
            // better than refusing to download a model there.
            closeQuietly(channel);
            return null;
        }
        if (lock == null) {
            // Another process holds it. This failure is about this file, not about the mirror, so
            // the message must not read like a broken download source.
            closeQuietly(channel);
            throw conflictPartial(destination);
        }
        return channel;
    }

    /**
     * Empties a partial file whose bytes cannot be resumed from, without unlinking it.
     *
     * <p>Deleting it while the claim is held makes the name unusable on Windows: an open handle
     * leaves the file delete-pending and the next open of that path fails, so the transfer that
     * follows would never start. For a resume the outcome is the same either way — the next attempt
     * begins at the first byte.
     */
    private static void resetPartial(java.nio.channels.FileChannel claim, Path partial)
            throws IOException {
        // Whatever the sidecar described is no longer the content of this file.
        ModelVerificationCache.forget(partial);
        if (claim == null) {
            Files.delete(partial);
            return;
        }
        claim.truncate(0L);
    }

    private static IOException conflictPartial(Path destination) {
        return new IOException("Another instance is already downloading "
                + destination.getFileName() + "; wait for it to finish and try again");
    }

    private static void releaseClaim(java.nio.channels.FileChannel claim) {
        if (claim == null) {
            return;
        }
        try {
            // Closing the channel is what releases the lock, and it is the only release point.
            claim.close();
        } catch (IOException ignored) {
            // The transfer is over either way, and a lock left behind dies with this process.
        }
    }

    private static void closeQuietly(java.nio.channels.FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing was claimed on it.
        }
    }

    /**
     * Drops a partial download whose bytes cannot be trusted.
     *
     * <p>A resume trusts {@code Files.size(partial)}, and nothing ever verified that prefix, so a
     * prefix that arrived corrupt survived every mirror and every restart and the asset could never
     * be installed. Only a length that is itself verified may be kept; with none available the file
     * is removed so the next attempt reads bytes that are checked again.
     *
     * @param keepBytes verified prefix length to keep, {@code 0} to remove the file
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
        // The peak is the partial plus the file it replaces: the verified partial is moved over the
        // destination at the end, so the old one is still on the volume until then. Ignoring it
        // passed a machine that then filled the partition during the final move.
        long replacedBytes = 0L;
        try {
            Path existing = destination.toAbsolutePath();
            if (Files.isRegularFile(existing)) {
                replacedBytes = Files.size(existing);
            }
        } catch (IOException | RuntimeException unknown) {
            replacedBytes = 0L;
        }
        DiskSpace.require(destination.toAbsolutePath().getParent(),
                neededBytes + replacedBytes, "download");
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
        // Read the state before the hash: remember() reads it afterwards, so a write landing inside
        // the reading window used to record the digest of the replaced bytes against the new state.
        BasicFileAttributes observed = ModelVerificationCache.observe(file);
        if (!expectedSha256.equalsIgnoreCase(sha256(file))) {
            return false;
        }
        ModelVerificationCache.rememberIfUnchanged(file, expectedSha256, observed);
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
        return CryptoSupport.hex(digest.digest());
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
