package org.universaltranslator.fabric;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TextKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-local surface classification used while nested HUD render methods run.
 *
 * <p>The mixin injections around a render method are a HEAD/RETURN pair and Mixin 0.8.x has no
 * exception-exit injection point, so a render method that throws between them never reaches its own
 * {@link #pop()} or {@link #popTextInput()}. Every latch therefore records when it was taken, and one
 * still held half a second later — longer than any real render pass — is dropped by the next push
 * instead of being nested on top of, so recovery happens inside the same frame rather than at the
 * next tick.
 */
public final class TranslationRenderContext {
    /**
     * How long a latch may be held before it is treated as leaked.
     *
     * <p>Measured on {@link System#nanoTime()}: the wall clock jumps backwards when NTP corrects it
     * or the player changes the time zone, and a latch held across such a jump looked fresh forever.
     */
    private static final long LEAK_NANOS = 500L * 1_000_000L;
    /** Deepest legitimate nesting of surface latches; deeper is a leak that never popped. */
    private static final int MAX_DEPTH = 16;

    private static final ThreadLocal<Deque<TextKind>> KINDS =
            new ThreadLocal<Deque<TextKind>>() {
                @Override
                protected Deque<TextKind> initialValue() {
                    return new ArrayDeque<TextKind>();
                }
            };
    private static final ThreadLocal<Integer> TEXT_INPUT_DEPTH =
            new ThreadLocal<Integer>();
    /** When each latch became active, in {@link System#nanoTime()}; 0 means "not held". */
    private static final ThreadLocal<long[]> LATCHED_AT =
            new ThreadLocal<long[]>() {
                @Override
                protected long[] initialValue() {
                    return new long[2];
                }
            };
    private static final AtomicBoolean LEAK_REPORTED = new AtomicBoolean();

    private static final int KIND_LATCH = 0;
    private static final int TEXT_INPUT_LATCH = 1;

    private TranslationRenderContext() {
    }

    public static void push(TextKind kind) {
        Deque<TextKind> kinds = KINDS.get();
        if (kinds.isEmpty()) {
            LATCHED_AT.get()[KIND_LATCH] = System.nanoTime();
        } else if (isLeaked(LATCHED_AT.get()[KIND_LATCH])) {
            // Only the leaked outermost latch: the stack nests legitimately, and clearing it threw
            // away the classifications the current frame had already pushed.
            kinds.removeLast();
            LATCHED_AT.get()[KIND_LATCH] = System.nanoTime();
        }
        if (kinds.size() >= MAX_DEPTH) {
            // Starting over is a new outermost latch, so it gets a timestamp of its own: keeping the
            // old one compared the recovery window against a latch that no longer exists, and zeroing
            // it left the leak detection off until the stack emptied again.
            kinds.clear();
            LATCHED_AT.get()[KIND_LATCH] = System.nanoTime();
        }
        kinds.push(kind);
    }

    public static void pop() {
        Deque<TextKind> kinds = KINDS.get();
        if (!kinds.isEmpty()) {
            kinds.pop();
        }
        if (kinds.isEmpty()) {
            // The deque stays on the thread; reset() is what releases it. Removing it here rebuilt
            // one deque plus its ThreadLocalMap entry for every surface of every frame.
            LATCHED_AT.get()[KIND_LATCH] = 0L;
        }
    }

    public static TextKind current() {
        Deque<TextKind> kinds = KINDS.get();
        return kinds.isEmpty() ? TextKind.OTHER : kinds.peek();
    }

    public static void pushTextInput() {
        long[] latchedAt = LATCHED_AT.get();
        Integer depth = TEXT_INPUT_DEPTH.get();
        int current = depth == null ? 0 : depth.intValue();
        if (current == 0) {
            latchedAt[TEXT_INPUT_LATCH] = System.nanoTime();
        } else if (isLeaked(latchedAt[TEXT_INPUT_LATCH])) {
            current = 0;
            latchedAt[TEXT_INPUT_LATCH] = System.nanoTime();
        }
        TEXT_INPUT_DEPTH.set(current + 1);
    }

    public static void popTextInput() {
        Integer depth = TEXT_INPUT_DEPTH.get();
        if (depth == null || depth <= 1) {
            TEXT_INPUT_DEPTH.remove();
            LATCHED_AT.get()[TEXT_INPUT_LATCH] = 0L;
        } else {
            TEXT_INPUT_DEPTH.set(depth - 1);
        }
    }

    public static boolean isTextInput() {
        Integer depth = TEXT_INPUT_DEPTH.get();
        return depth != null && depth > 0;
    }

    /**
     * Clears the classification latched on the current thread.
     *
     * <p>Called once per client tick, before the frame is rendered, as the backstop for a render
     * method that never runs again and therefore never reaches its own repair.
     */
    public static void reset() {
        KINDS.remove();
        TEXT_INPUT_DEPTH.remove();
        LATCHED_AT.remove();
    }

    private static boolean isLeaked(long latchedSince) {
        if (latchedSince == 0L) {
            return false;
        }
        long heldNanos = System.nanoTime() - latchedSince;
        if (heldNanos <= LEAK_NANOS) {
            return false;
        }
        if (LEAK_REPORTED.compareAndSet(false, true)) {
            DebugLog.global().log("render-context", "recovered", "leaked-latch",
                    "held-millis", Long.toString(heldNanos / 1_000_000L));
        }
        return true;
    }
}
