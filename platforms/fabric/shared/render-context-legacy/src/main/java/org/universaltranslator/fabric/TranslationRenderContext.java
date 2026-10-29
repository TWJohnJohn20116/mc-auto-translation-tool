package org.universaltranslator.fabric;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TextKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-local surface classification used while nested HUD render methods run.
 *
 * <p>Same contract as the copy in {@code shared/common}, and the same fix: the HEAD/RETURN pairs
 * around the legacy render methods have no exception exit in Mixin 0.8.x, so a throw between them
 * used to leave the classification latched until the next tick. Every latch now records when it was
 * taken, and one still held {@link #LEAK_MILLIS} later — longer than any real render pass — is
 * dropped by the next push instead of being nested on top of, so recovery happens inside the same
 * frame.
 */
public final class TranslationRenderContext {
    /** How long a latch may be held before it is treated as leaked. */
    private static final long LEAK_MILLIS = 500L;

    private static final ThreadLocal<Deque<TextKind>> KINDS =
            new ThreadLocal<Deque<TextKind>>() {
                @Override
                protected Deque<TextKind> initialValue() {
                    return new ArrayDeque<TextKind>();
                }
            };
    private static final ThreadLocal<Integer> TEXT_INPUT_DEPTH =
            new ThreadLocal<Integer>();
    /** When each latch became active, in {@link System#currentTimeMillis()}; 0 means "not held". */
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
            LATCHED_AT.get()[KIND_LATCH] = System.currentTimeMillis();
        } else if (isLeaked(LATCHED_AT.get()[KIND_LATCH])) {
            kinds.clear();
            LATCHED_AT.get()[KIND_LATCH] = System.currentTimeMillis();
        }
        kinds.push(kind);
    }

    public static void pop() {
        Deque<TextKind> kinds = KINDS.get();
        if (!kinds.isEmpty()) {
            kinds.pop();
        }
        if (kinds.isEmpty()) {
            KINDS.remove();
            LATCHED_AT.get()[KIND_LATCH] = 0L;
        }
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

    public static TextKind current() {
        Deque<TextKind> kinds = KINDS.get();
        return kinds.isEmpty() ? TextKind.OTHER : kinds.peek();
    }

    public static void pushTextInput() {
        long[] latchedAt = LATCHED_AT.get();
        Integer depth = TEXT_INPUT_DEPTH.get();
        int current = depth == null ? 0 : depth.intValue();
        if (current == 0) {
            latchedAt[TEXT_INPUT_LATCH] = System.currentTimeMillis();
        } else if (isLeaked(latchedAt[TEXT_INPUT_LATCH])) {
            current = 0;
            latchedAt[TEXT_INPUT_LATCH] = System.currentTimeMillis();
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

    private static boolean isLeaked(long latchedSince) {
        if (latchedSince == 0L) {
            return false;
        }
        if (System.currentTimeMillis() - latchedSince <= LEAK_MILLIS) {
            return false;
        }
        if (LEAK_REPORTED.compareAndSet(false, true)) {
            DebugLog.global().log("render-context", "recovered", "leaked-latch",
                    "held-millis", Long.toString(System.currentTimeMillis() - latchedSince));
        }
        return true;
    }
}
