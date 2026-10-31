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
    /**
     * Longest the store flush's own write may take: the snapshot, the {@code Properties.store} of up
     * to the whole entry cap, the temporary file and the atomic move that publishes it, and on the
     * merge path one more read of the file on disk.
     *
     * <p>That write is not a wait that can be bounded from inside, so it used to be covered by nothing
     * but the slack at the end of the sum below -- while it is the one piece of the teardown whose
     * interruption loses data instead of time.
     */
    public static final long CACHE_WRITE_MILLIS = 3_000L;
    /**
     * Longest a session's provider teardown may take after its workers have drained. It is the only
     * placeholder the budget has for that stage: a close that got its workers back tears the provider
     * down on the thread that called it, and one that did not leaves it to the worker that leaves last
     * -- neither hands it to a thread of its own, so nothing waits on the call any more, and the number
     * stays so that the two sums below still describe the same teardown.
     */
    public static final long PROVIDER_CLOSE_MILLIS = 1_000L;
    /**
     * How far {@link #HOOK_JOIN_MILLIS} may overshoot the three stages it adds up. Those three are
     * estimates rather than measured bounds, and this is the only slack they get, so it is a decision
     * here instead of a bare literal buried in the sum below.
     */
    public static final long HOOK_SLACK_MILLIS = 500L;
    /**
     * Longest a client's stop hook waits for the teardown thread it started.
     *
     * <p>Long enough for everything that thread does <em>before</em> the worker wait: the
     * configuration write it drains first, then the session close's store flush -- which may wait
     * {@link #CACHE_FLUSH_WAIT_MILLIS} for a read still in flight and then spends up to
     * {@link #CACHE_WRITE_MILLIS} writing the deferred burst. That flush now runs before the close
     * waits for its workers, so a hook that stops waiting here loses the wait and never the burst: the
     * entries are already on disk, and the workers it stops waiting for were interrupted and can only
     * report failures nobody reads. The provider teardown it also drops is covered on the way out by
     * the offline provider's own JVM shutdown hook.
     *
     * <p>Shorter than {@link #TEARDOWN_JOIN_MILLIS} on purpose, by
     * {@code SESSION_CLOSE_MILLIS + PROVIDER_CLOSE_MILLIS + 1_000L - HOOK_SLACK_MILLIS}: the worker
     * wait and the provider teardown are the two stages a stop hook gives up on, so this constant is
     * what every seam in the trees joins on and the only budget a reader can check against the work
     * those seams cover. Nothing joins on {@link #TEARDOWN_JOIN_MILLIS}, and the offline provider's
     * own JVM shutdown hook is what closes its child once this budget runs out.
     */
    public static final long HOOK_JOIN_MILLIS = CONFIG_WRITE_MILLIS
            + CACHE_FLUSH_WAIT_MILLIS + CACHE_WRITE_MILLIS + HOOK_SLACK_MILLIS;
    /**
     * Longest a teardown that cannot be cut off may take: the configuration write, the session close
     * with its worker wait and store flush, and the provider teardown.
     *
     * <p>No seam joins on it -- every stop hook waits {@link #HOOK_JOIN_MILLIS}, which leaves the
     * worker wait and the provider teardown outside the time those hooks spend. It stays as the full
     * bound the split is measured against, so the relation between the two constants can be checked
     * right here: this sum adds {@code SESSION_CLOSE_MILLIS}, {@code PROVIDER_CLOSE_MILLIS} and one
     * second of slack where the other adds {@link #HOOK_SLACK_MILLIS}, so
     * {@code HOOK_JOIN_MILLIS < TEARDOWN_JOIN_MILLIS} holds by
     * {@code SESSION_CLOSE_MILLIS + PROVIDER_CLOSE_MILLIS + 1_000L - HOOK_SLACK_MILLIS}.
     */
    public static final long TEARDOWN_JOIN_MILLIS = CONFIG_WRITE_MILLIS + SESSION_CLOSE_MILLIS
            + CACHE_FLUSH_WAIT_MILLIS + CACHE_WRITE_MILLIS + PROVIDER_CLOSE_MILLIS + 1_000L;

    private ShutdownBudget() {
    }
}
