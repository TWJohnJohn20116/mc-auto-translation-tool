package org.universaltranslator.forge.legacy;

import org.universaltranslator.core.TextKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/** Thread-local render category set by legacy ASM hooks. */
public final class LegacyRenderContext {
    private static final ThreadLocal<Deque<TextKind>> KINDS =
            new ThreadLocal<Deque<TextKind>>() {
                @Override
                protected Deque<TextKind> initialValue() {
                    return new ArrayDeque<TextKind>();
                }
            };
    private static final ThreadLocal<Integer> TEXT_INPUT_DEPTH =
            new ThreadLocal<Integer>();
    /** Strings this frame already produced as a translation result. See {@link #beginFrame()}. */
    private static final ThreadLocal<Set<String>> TRANSLATED_RESULTS =
            new ThreadLocal<Set<String>>() {
                @Override
                protected Set<String> initialValue() {
                    // Insertion ordered so a full set drops its oldest entry instead of every entry.
                    return new LinkedHashSet<String>();
                }
            };
    /** Cap on {@link #TRANSLATED_RESULTS}; a frame cannot legitimately need more. */
    private static final int MAX_FRAME_TRANSLATIONS = 512;

    private LegacyRenderContext() {
    }

    public static void pushChat() {
        KINDS.get().push(TextKind.CHAT);
    }

    public static void pushTooltip() {
        KINDS.get().push(TextKind.TOOLTIP);
    }

    /** Prevents the global FontRenderer hook from translating locally typed text. */
    public static void pushTextInput() {
        Integer depth = TEXT_INPUT_DEPTH.get();
        TEXT_INPUT_DEPTH.set(depth == null ? 1 : depth + 1);
    }

    public static void popTextInput() {
        Integer depth = TEXT_INPUT_DEPTH.get();
        if (depth == null || depth <= 1) {
            TEXT_INPUT_DEPTH.remove();
        } else {
            TEXT_INPUT_DEPTH.set(depth - 1);
        }
    }

    static boolean isTextInput() {
        Integer depth = TEXT_INPUT_DEPTH.get();
        return depth != null && depth > 0;
    }

    public static void pop() {
        Deque<TextKind> kinds = KINDS.get();
        if (!kinds.isEmpty()) {
            kinds.pop();
        }
        if (kinds.isEmpty()) {
            KINDS.remove();
        }
    }

    static TextKind current() {
        Deque<TextKind> kinds = KINDS.get();
        return kinds.isEmpty() ? TextKind.OTHER : kinds.peek();
    }

    /**
     * Records a string this bridge just produced as a translation.
     *
     * <p>The {@code FontRenderer} hook is installed on both {@code drawString} and
     * {@code getStringWidth}, and {@code drawCenteredString} draws through both, so one centred line
     * reached the bridge two or three times per frame — and every call after the first re-translated
     * the already translated result. Remembering the output makes those repeats a set lookup.
     */
    static void rememberTranslation(String translated) {
        if (translated == null) {
            return;
        }
        Set<String> results = TRANSLATED_RESULTS.get();
        if (results.size() >= MAX_FRAME_TRANSLATIONS) {
            // Drop the oldest entry rather than the whole frame. Clearing made a dense frame forget
            // the lines it had just produced, and those lines then went back through the whole
            // translation path on the next frame.
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

    /**
     * Marks the start of a client tick, discarding whatever the previous frame left latched.
     *
     * <p>Both stacks are frame-scoped and both can be left behind by a render method that threw
     * between its push and its pop: the ASM hooks below wrap the body in {@code try/finally}, but a
     * {@code ThreadLocal} can also survive a class-loading failure or a re-entrant call that never
     * returns. Clearing them once per tick makes such a leak cost one frame instead of the session.
     * Without this a leaked {@link TextKind} classified every later string as that kind (chat drawn
     * as tooltips, tooltips as chat) and a leaked text-input depth stopped translation entirely
     * until the game was restarted.
     */
    public static void beginFrame() {
        TEXT_INPUT_DEPTH.remove();
        KINDS.remove();
        TRANSLATED_RESULTS.remove();
    }
}
