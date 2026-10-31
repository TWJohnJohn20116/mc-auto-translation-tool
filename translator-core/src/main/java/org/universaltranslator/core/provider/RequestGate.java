package org.universaltranslator.core.provider;

import java.util.concurrent.TimeUnit;

/**
 * The "at most one request per interval" gate two providers had a byte-identical copy of.
 *
 * <p>{@link ResilientTranslationProvider} and {@link TencentHunyuanProvider} both held a
 * {@code nextRequestAtMillis} field, a claim-under-lock loop and a sleep-outside-the-lock comment
 * describing why a caller that has to wait must not block the others. Two copies of a
 * concurrency primitive is how they end up disagreeing about which instant is claimed, so the gate
 * is one class now. Each provider still owns its own instance: the interval of one provider says
 * nothing about the interval of another.
 *
 * <p>Claiming is atomic: two callers can never pass the gate for the same slot. Sleeping happens
 * outside the lock, so a caller that waits does not stop the others from reserving their own (later)
 * slots, and the value is re-read on wake-up so a late wake still pushes the following slot.
 *
 * <p>The slot is an instant on {@link System#nanoTime()}, not on the wall clock. The wall clock is
 * corrected by NTP, by the user, and by a resume from sleep, and both directions break this gate: a
 * jump backwards passes every request for as long as the jump lasted, a jump forward parks the
 * caller for it. The gate exists to keep an endpoint from seeing a burst, so the failure direction
 * matters. The wait is also sliced, so a closed session is noticed within one slice instead of after
 * a whole interval.
 */
final class RequestGate {
    /** Granularity of one wait slice, so shutdown is noticed during a long interval. */
    private static final long WAIT_SLICE_MILLIS = 100L;

    private final long minimumIntervalNanos;
    /** Monotonic instant of the next free slot; meaningless until {@link #claimed} is set. */
    private long nextRequestAtNanos;
    /** Whether a slot was ever claimed; {@code nanoTime()} may start below any fixed value. */
    private boolean claimed;

    RequestGate(long minimumIntervalMillis) {
        this.minimumIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, minimumIntervalMillis));
    }

    /** Blocks until this caller owns its slot, or fails when the thread was cancelled. */
    void await() throws InterruptedException {
        while (true) {
            long waitMillis;
            synchronized (this) {
                long now = System.nanoTime();
                if (!claimed || now - nextRequestAtNanos >= 0L) {
                    claim(now);
                    return;
                }
                waitMillis = TimeUnit.NANOSECONDS.toMillis(nextRequestAtNanos - now);
            }
            Thread.sleep(Math.min(WAIT_SLICE_MILLIS, Math.max(1L, waitMillis)));
        }
    }

    private void claim(long now) {
        claimed = true;
        nextRequestAtNanos = now + minimumIntervalNanos;
    }
}
