package org.universaltranslator.core.provider;

/**
 * The "at most one request per interval" gate two providers had a byte-identical copy of.
 *
 * <p>{@link ResilientTranslationProvider} and {@link TencentHunyuanProvider} both held a
 * {@code nextRequestAtMillis} field, a claim-under-lock loop and a sleep-outside-the-lock comment
 * describing why a caller that has to wait must not block the others. Two copies of a
 * concurrency primitive is how they end up disagreeing about which instant is claimed, so the gate
 * is one class now.
 *
 * <p>Claiming is atomic: two callers can never pass the gate for the same slot. Sleeping happens
 * outside the lock, so a caller that waits does not stop the others from reserving their own (later)
 * slots, and the value is re-read on wake-up so a late wake still pushes the following slot.
 */
final class RequestGate {
    private final long minimumIntervalMillis;
    private long nextRequestAtMillis;

    RequestGate(long minimumIntervalMillis) {
        this.minimumIntervalMillis = Math.max(0L, minimumIntervalMillis);
    }

    /** Blocks until this caller owns its slot, or fails when the thread was cancelled. */
    void await() throws InterruptedException {
        while (true) {
            long wait;
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (nextRequestAtMillis <= now) {
                    nextRequestAtMillis = now + minimumIntervalMillis;
                    return;
                }
                wait = nextRequestAtMillis - now;
            }
            Thread.sleep(wait);
        }
    }
}
