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
 * <p>The classification is pushed at the {@code HEAD} of a render method and popped at its
 * {@code RETURN}. Mixin 0.8.x has no exception-exit injection point, so a render method that throws
 * between the two never runs its pop — and the nine call sites therefore used to depend on the
 * once-per-tick {@link #reset()} to clean up after a third-party mixin threw inside
 * {@code ChatHud.render}. Between the throw and that tick boundary every string of the frame was
 * misclassified, and a leaked text-input depth returns every string untranslated.
 *
 * <p>Each latch now records when it was taken. A latch that is still held half a second later
 * cannot be a render call in progress — no single widget or HUD pass takes that long — so the next
 * push drops it instead of nesting on top of it, which recovers within the same frame instead of at
 * the next tick. Only the outermost push records a time, so ordinary nesting is never disturbed.
 */
public final class TranslationRenderContext {
    /**
     * How long a latch may be held before it is treated as leaked.
     *
     * <p>Measured on {@link System#nanoTime()}: the wall clock jumps backwards when NTP corrects it
     * or the player changes the time zone, and a latch held across such a jump looked fresh forever,
     * which is the one case this mechanism exists for. A render pass is measured in milliseconds and
     * a user's patience in seconds, so five hundred of them is far above any real pass and far below
     * the point where the leak becomes visible.
     */
    private static final long LEAK_NANOS = 500L * 1_000_000L;
    /**
     * Deepest legitimate nesting of surface latches.
     *
     * <p>A HUD pass nests a couple of times; a stack deeper than this is a leak that never popped,
     * and the only safe recovery is to start over rather than to grow without bound.
     */
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
     * "the right output for this string is the string itself", which holds for every surface and
     * every style, so no key has to be built and nothing is allocated to look one up.
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
            // Drop the leaked outermost latch only. The stack nests legitimately — a HUD pass
            // around a chat pass — and clearing it threw away every classification the current
            // frame had already pushed, so the rest of the frame was rendered as OTHER.
            if (!kinds.isEmpty()) {
                kinds.removeLast();
            }
            LATCHED_AT.get()[KIND_LATCH] = System.nanoTime();
        }
        if (kinds.size() >= MAX_DEPTH) {
            // Starting over is a new outermost latch, so it gets a timestamp of its own. Leaving the
            // old one behind compared the recovery window against a latch that no longer exists, and
            // zeroing it left the leak detection off until the stack emptied again.
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
            // The deque itself stays on the thread: this runs for every surface of every frame, and
            // removing it meant re-allocating one deque plus its ThreadLocalMap entry per surface per
            // frame while the very next call rebuilt them. reset() is what releases it.
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
     * <p>Still called once per client tick, before the frame is rendered: it is the backstop that
     * covers anything the per-latch check above cannot see, such as a render method that never runs
     * again. See the class comment for the per-latch recovery that makes a leak survive at most one
     * frame.
     */
    public static void reset() {
        KINDS.remove();
        TEXT_INPUT_DEPTH.remove();
        LATCHED_AT.remove();
        TRANSLATED_RESULTS.remove();
        UNCHANGED_INPUTS.remove();
    }

    /**
     * Records a string the render bridge just produced as a translation.
     *
     * <p>{@code DrawContext.drawText*} measures through {@code TextRenderer.getWidth} and then
     * draws, and both are hooked, so the second and third call received the string the bridge had
     * just returned and translated it again. Keeping the output turns those repeats into one set
     * lookup instead of a second provider request.
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
     * <p>A chat line or a scoreboard row is handed to the drawing hook and then to the measuring
     * hook as the same component, and both hooks translate. Lines that stay as they are — already in
     * the target language, a blocked keyword, no provider answer yet — therefore paid for two whole
     * passes each frame. Remembering the input turns the second pass into one set lookup; the
     * measuring hook draws the same bytes either way, so the width it reports does not change.
     *
     * <p>One frame of staleness is the only cost: a line the provider answers mid-frame keeps its
     * original until the next client tick, which is when {@link #reset()} empties the set.
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
            // Once per session, and only with the debug log enabled: turn it on to confirm that a
            // third-party mixin is throwing inside a render method, which is the usual cause.
            DebugLog.global().log("render-context", "recovered", "leaked-latch",
                    "held-millis", Long.toString(heldNanos / 1_000_000L));
        }
        return true;
    }
}
