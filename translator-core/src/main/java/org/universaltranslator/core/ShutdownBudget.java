package org.universaltranslator.core;

/**
 * How long the mod may spend shutting down, in one place.
 *
 * <p>The numbers used to live apart: {@link TranslationCoordinator#close()} waited up to five seconds
 * for its workers, each configuration writer two, and every Fabric client joined the teardown thread
 * with a budget of its own that was shorter than the wait inside it. A worker parked in a provider
 * read therefore made the join return while the session close -- and the store flush inside it, the
 * only chance a burst of deferred entries gets -- was still running, and the process was halted on top
 * of it.
 *
 * <p>Keeping them here makes "the outer wait is longer than the inner one" something a reader can
 * check, instead of three literals in three trees to compare by hand.
 */
public final class ShutdownBudget {
    /** Longest {@link TranslationCoordinator#close()} waits for its workers to drain. */
    public static final long SESSION_CLOSE_MILLIS = 5_000L;
    /** Longest a configuration writer waits for the writes already queued when it stops. */
    public static final long CONFIG_WRITE_MILLIS = 2_000L;
    /**
     * Longest a session's store flush waits for a read that is still in flight before it writes its
     * burst anyway. The disk store owns the wait itself (its load timed out by this number); a change
     * there has to come back here, or the budget below stops covering it.
     */
    public static final long CACHE_FLUSH_WAIT_MILLIS = 2_000L;
    /** Longest a session's provider teardown may take after its workers have drained. */
    public static final long PROVIDER_CLOSE_MILLIS = 1_000L;
    /**
     * Longest a client's stop hook waits for the teardown thread it started.
     *
     * <p>Deliberately longer than everything that thread does inside the budget: the configuration
     * write it drains first, the session close that waits {@link #SESSION_CLOSE_MILLIS} for its
     * workers, the store flush that follows the close (which may wait
     * {@link #CACHE_FLUSH_WAIT_MILLIS} for a read still in flight before writing the deferred burst),
     * and the provider teardown. A close that is cut off halfway leaves the store flush inside it
     * unfinished, and no later path writes that burst.
     */
    public static final long TEARDOWN_JOIN_MILLIS = CONFIG_WRITE_MILLIS + SESSION_CLOSE_MILLIS
            + CACHE_FLUSH_WAIT_MILLIS + PROVIDER_CLOSE_MILLIS + 1_000L;

    private ShutdownBudget() {
    }
}
