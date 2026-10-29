package org.universaltranslator.fabric;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TextKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-local surface classification used while nested HUD render methods run.
 *
 * <p>The classification is pushed at the {@code HEAD} of a render method and popped at its
 * {@code RETURN}. Mixin 0.8.x has no exception-exit injection point, so a render method that throws
 * between the two never runs its pop — and the nine call sites therefore used to depend on the
 * once-per-tick {@link #reset()} to clean up after a third-party mixin threw inside
 * {@code ChatHud.render}. Between the throw and that tick boundary every string of the frame was
 * misclassified, and a leaked text-input depth returns every string untranslated.
 *
 * <p>Each latch now records when it was taken. A latch that is still held {@link #LEAK_MILLIS} later
 * cannot be a render call in progress — no single widget or HUD pass takes that long — so the next
 * push drops it instead of nesting on top of it, which recovers within the same frame instead of at
 * the next tick. Only the outermost push records a time, so ordinary nesting is never disturbed.
 */
public final class TranslationRenderContext {
    /**
     * How long a latch may be held before it is treated as leaked.
     *
     * <p>Deliberately far above any real render pass and far below a user's patience: a chat box or
     * HUD surface is measured in milliseconds, and the damage a leak does — everything translated
     * as the wrong surface, or nothing translated at all — lasts until the check fires.
     */
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

    public static TextKind current() {
        Deque<TextKind> kinds = KINDS.get();
        return kinds.isEmpty() ? TextKind.OTHER : kinds.peek();
    }

    /** Prevents locally typed text from entering the global render translation path. */
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

    /**
     * Clears the classification latched on the current thread.
     *
     * <p>Still called once per client tick, before the frame is rendered: it is the backstop that
     * covers anything the per-latch check above cannot see, such as a render method that never runs
     * again. See the class comment for the per-latch recovery that makes a leak survive at most one
     * frame.
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
        if (System.currentTimeMillis() - latchedSince <= LEAK_MILLIS) {
            return false;
        }
        if (LEAK_REPORTED.compareAndSet(false, true)) {
            // Once per session, and only with the debug log enabled: turn it on to confirm that a
            // third-party mixin is throwing inside a render method, which is the usual cause.
            DebugLog.global().log("render-context", "recovered", "leaked-latch",
                    "held-millis", Long.toString(System.currentTimeMillis() - latchedSince));
        }
        return true;
    }
}
