package org.universaltranslator.core.offline;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one place that decides how much room an offline install has to leave free.
 *
 * <p>The same invariant — "keep 64 MiB on the volume after writing" — used to be written three
 * times with three behaviours: the downloader required only the missing remainder, the alias copy
 * required the whole file, and the extractor silently lowered its budget to zero. One shortage then
 * surfaced as three different messages, two of which pointed at the wrong cause, and any future
 * change to the reserve had to be found in three files.
 *
 * <p>Package-private on purpose: {@code translator-core} is mounted into every platform tree, and
 * this is an implementation detail of the offline installer.
 */
final class DiskSpace {
    /** Always kept free on the destination volume, so an install cannot fill the partition. */
    static final long RESERVE_BYTES = 64L * 1024L * 1024L;

    private DiskSpace() {
    }

    /**
     * @return free bytes on the volume holding {@code directory}, or {@code -1} when the platform
     *         cannot report them
     */
    static long usable(Path directory) {
        try {
            FileStore store = Files.getFileStore(directory);
            return store.getUsableSpace();
        } catch (IOException | RuntimeException unavailable) {
            return -1L;
        }
    }

    /**
     * Refuses work that cannot finish while still leaving the reserve behind.
     *
     * @param directory directory the bytes land in
     * @param extraBytes bytes this step still has to add
     * @param subject what needs the room, phrased for the user ("offline model copy")
     */
    static void require(Path directory, long extraBytes, String subject) throws IOException {
        long usable = usable(directory);
        if (usable >= 0L && usable < extraBytes + RESERVE_BYTES) {
            throw new IOException("Not enough free space for the " + subject + ": "
                    + extraBytes + " bytes needed, " + usable + " available");
        }
    }
}
