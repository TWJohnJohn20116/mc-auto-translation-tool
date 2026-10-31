package org.universaltranslator.forge;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TextKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-local surface classification used while nested HUD render methods run.
 *
 * <p>Each latch records when it was taken. A latch still held {@link #LEAK_NANOS} later cannot be a
 * render call in progress, so the next push drops it instead of nesting on top of it, which recovers
 * within the same frame instead of at the next tick. Mixin 0.8.x has no exception-exit injection
 * point, so a third-party mixin throwing inside a render method never runs the matching pop.
 */
public final class TranslationRenderContext {
    /**
     * How long a latch may be held before it is treated as leaked.
     *
     * <p>Measured on {@link System#nanoTime()}: the wall clock jumps backwards when NTP corrects it
     * or the player changes the time zone, and a latch held across such a jump looked fresh forever,
     * while a forward jump made a healthy latch look leaked.
     */
    private static final long LEAK_NANOS = 500L * 1_000_000L;

    private static final ThreadLocal<Deque<TextKind>> KINDS =
            new ThreadLocal<Deque<TextKind>>() {
                @Override
                protected Deque<TextKind> initialValue() {
                    return new ArrayDeque<TextKind>();
                }
            };
    /** Nesting depth of {@link #pushTextInput()}; an array so the depth never boxes per push. */
    private static final ThreadLocal<int[]> TEXT_INPUT_DEPTH =
            new ThreadLocal<int[]>();
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

    /**
     * Strings this frame already produced as a translation result. See {@link #isTranslatedResult}.
     *
     * <p>Insertion ordered so a full set drops its oldest entry instead of every entry: clearing it
     * made a dense frame forget the lines it had just produced, and those lines then went back
     * through the whole translation path on the next frame.
     */
    private static final ThreadLocal<Set<String>> TRANSLATED_RESULTS =
            new ThreadLocal<Set<String>>() {
                @Override
                protected Set<String> initialValue() {
                    return new LinkedHashSet<String>();
                }
            };
    /** Cap on {@link #TRANSLATED_RESULTS}; one frame cannot legitimately need more. */
    private static final int MAX_FRAME_TRANSLATIONS = 512;

    /** Window counter advanced by {@link #beginFrame()}; the render memos key their entries on it. */
    private static volatile int frameEpoch;

    private TranslationRenderContext() {
    }

    public static void push(TextKind kind) {
        Deque<TextKind> kinds = KINDS.get();
        long[] latchedAt = LATCHED_AT.get();
        if (kinds.isEmpty()) {
            latchedAt[KIND_LATCH] = System.nanoTime();
        } else if (isLeaked(latchedAt[KIND_LATCH])) {
            kinds.clear();
            latchedAt[KIND_LATCH] = System.nanoTime();
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
        int[] depth = TEXT_INPUT_DEPTH.get();
        if (depth == null) {
            depth = new int[1];
            TEXT_INPUT_DEPTH.set(depth);
        }
        if (depth[0] == 0) {
            latchedAt[TEXT_INPUT_LATCH] = System.nanoTime();
        } else if (isLeaked(latchedAt[TEXT_INPUT_LATCH])) {
            // A leaked depth means nothing renders as typed text for the rest of the session, so
            // the count restarts from this push rather than staying latched.
            depth[0] = 0;
            latchedAt[TEXT_INPUT_LATCH] = System.nanoTime();
        }
        depth[0]++;
    }

    public static void popTextInput() {
        int[] depth = TEXT_INPUT_DEPTH.get();
        if (depth == null || depth[0] <= 1) {
            TEXT_INPUT_DEPTH.remove();
            LATCHED_AT.get()[TEXT_INPUT_LATCH] = 0L;
        } else {
            depth[0]--;
        }
    }

    public static boolean isTextInput() {
        int[] depth = TEXT_INPUT_DEPTH.get();
        return depth != null && depth[0] > 0;
    }

    /** Counter of the current tick window, used as the memo key of the render lookups. */
    public static int frameEpoch() {
        return frameEpoch;
    }

    /**
     * Marks the start of a client tick, discarding whatever a previous frame left latched.
     *
     * <p>Both stacks are frame-scoped and both can be left behind by a render method that threw
     * between its push and its pop. Clearing only the text-input depth meant a leaked
     * {@link TextKind} survived the whole session: every later string was then classified as that
     * kind, so chat lines were translated as tooltips and tooltips as chat. The Fabric tree already
     * resets both on every tick for the same reason.
     *
     * <p>The translated-result set is cleared here too: it only exists to spare the repeats of one
     * frame (see {@link #rememberTranslation}), so it must never outlive the frame.
     */
    public static void beginFrame() {
        frameEpoch++;
        int[] depth = TEXT_INPUT_DEPTH.get();
        if (depth != null) {
            depth[0] = 0;
        }
        LATCHED_AT.get()[TEXT_INPUT_LATCH] = 0L;
        KINDS.remove();
        LATCHED_AT.get()[KIND_LATCH] = 0L;
        TRANSLATED_RESULTS.remove();
    }

    /**
     * Records a string the render bridge just produced as a translation.
     *
     * <p>{@code GuiGraphics.drawCenteredString} measures through {@code Font.width} and then draws
     * through {@code GuiGraphics.drawString}, and every one of those is hooked, so the second and
     * third call received the string the bridge had just returned and translated it again. Keeping
     * the output turns those repeats into one set lookup instead of a second provider request.
     */
    static void rememberTranslation(String translated) {
        if (translated == null) {
            return;
        }
        Set<String> results = TRANSLATED_RESULTS.get();
        if (results.size() >= MAX_FRAME_TRANSLATIONS) {
            Iterator<String> oldest = results.iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        results.add(translated);
    }

    /** True when {@code text} is a translation this bridge produced earlier in the same frame. */
    static boolean isTranslatedResult(String text) {
        return text != null && TRANSLATED_RESULTS.get().contains(text);
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
            // Once per session and only with the debug log enabled; the usual cause is a
            // third-party mixin throwing between a render method's push and its pop.
            DebugLog.global().log("render-context", "recovered", "leaked-latch",
                    "held-millis", Long.toString(heldNanos / 1_000_000L));
        }
        return true;
    }
}
