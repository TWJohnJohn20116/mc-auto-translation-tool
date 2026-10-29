package org.universaltranslator.forge;

import org.universaltranslator.core.TextKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** Thread-local surface classification used while nested HUD render methods run. */
public final class TranslationRenderContext {
    private static final ThreadLocal<Deque<TextKind>> KINDS =
            new ThreadLocal<Deque<TextKind>>() {
                @Override
                protected Deque<TextKind> initialValue() {
                    return new ArrayDeque<TextKind>();
                }
            };
    private static final ThreadLocal<Integer> TEXT_INPUT_DEPTH =
            new ThreadLocal<Integer>();
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

    private TranslationRenderContext() {
    }

    public static void push(TextKind kind) {
        KINDS.get().push(kind);
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

    public static TextKind current() {
        Deque<TextKind> kinds = KINDS.get();
        return kinds.isEmpty() ? TextKind.OTHER : kinds.peek();
    }

    /** Prevents locally typed text from entering the global render translation path. */
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

    public static boolean isTextInput() {
        Integer depth = TEXT_INPUT_DEPTH.get();
        return depth != null && depth > 0;
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
        TEXT_INPUT_DEPTH.remove();
        KINDS.remove();
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
            results.clear();
        }
        results.add(translated);
    }

    /** True when {@code text} is a translation this bridge produced earlier in the same frame. */
    static boolean isTranslatedResult(String text) {
        return text != null && TRANSLATED_RESULTS.get().contains(text);
    }
}
