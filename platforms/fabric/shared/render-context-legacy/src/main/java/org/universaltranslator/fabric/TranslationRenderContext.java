package org.universaltranslator.fabric;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TextKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-local surface classification used while nested HUD render methods run.
 *
 * <p>Same contract as the copy in {@code shared/common}, and the same fix: the HEAD/RETURN pairs
 * around the legacy render methods have no exception exit in Mixin 0.8.x, so a throw between them
 * used to leave the classification latched until the next tick. Every latch now records when it was
 * taken, and one still held half a second later — longer than any real render pass — is dropped by
 * the next push instead of being nested on top of, so recovery happens inside the same frame.
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
    /** Strings this frame already produced as a translation result. See {@link #isTranslatedResult}. */
    private static final ThreadLocal<Set<String>> TRANSLATED_RESULTS =
            new ThreadLocal<Set<String>>() {
                @Override
                protected Set<String> initialValue() {
                    return new HashSet<String>();
                }
            };
    /** Cap on {@link #TRANSLATED_RESULTS}; one frame cannot legitimately need more. */
    private static final int MAX_FRAME_TRANSLATIONS = 512;
    /**
     * Inputs this frame already rendered unchanged. See {@link #rememberUnchanged}.
     *
     * <p>Kept apart from {@link #TRANSLATED_RESULTS} because the answer differs: an entry here says
     * "the right output for this string is the string itself", which holds for every surface, so no
     * key has to be built and nothing is allocated to look one up. The modern context has had this
     * set from the start; without it the 1.0–1.13 trees ran the whole guard chain twice per line for
     * every line that stays as it is, which is most of them.
     */
    private static final ThreadLocal<Set<String>> UNCHANGED_INPUTS =
            new ThreadLocal<Set<String>>() {
                @Override
                protected Set<String> initialValue() {
                    return new HashSet<String>();
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
            if (!kinds.isEmpty()) {
                kinds.removeLast();
            }
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
        TRANSLATED_RESULTS.remove();
        UNCHANGED_INPUTS.remove();
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
     * Records a string the render bridge just produced as a translation.
     *
     * <p>The legacy {@code TextRenderer} hooks fire once for the measurement and again for the draw
     * of the same line, so the second call received the string the bridge had just returned and
     * translated it a second time.
     */
    static void rememberTranslation(String translated) {
        if (translated == null) {
            return;
        }
        Set<String> results = TRANSLATED_RESULTS.get();
        if (results.size() >= MAX_FRAME_TRANSLATIONS) {
            results.clear();
        }
        results.add(translated);
    }

    /** True when {@code text} is a translation this bridge produced earlier in the same frame. */
    static boolean isTranslatedResult(String text) {
        return text != null && TRANSLATED_RESULTS.get().contains(text);
    }

    /**
     * Records a string the render bridge found nothing to translate in.
     *
     * <p>The legacy {@code TextRenderer} hooks fire once for the measurement and again for the draw
     * of the same line, and the bridge translates both times. A line that stays as it is — already in
     * the target language, a blocked keyword, no provider answer yet — therefore paid for two whole
     * guard chains and two lookups per frame. See the copy in {@code shared/common}.
     */
    static void rememberUnchanged(String original) {
        if (original == null) {
            return;
        }
        Set<String> unchanged = UNCHANGED_INPUTS.get();
        if (unchanged.size() >= MAX_FRAME_TRANSLATIONS) {
            unchanged.clear();
        }
        unchanged.add(original);
    }

    /** True when {@code original} already rendered as itself earlier in the same frame. */
    static boolean isUnchangedResult(String original) {
        return original != null && UNCHANGED_INPUTS.get().contains(original);
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
