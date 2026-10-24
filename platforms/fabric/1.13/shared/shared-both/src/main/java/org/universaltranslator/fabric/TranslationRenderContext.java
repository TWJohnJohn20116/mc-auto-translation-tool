package org.universaltranslator.fabric;

import org.universaltranslator.core.TextKind;

import java.util.ArrayDeque;
import java.util.Deque;

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
     * Clears the classification latched on the current thread.
     *
     * <p>The mixin injections around a render method are a HEAD/RETURN pair, and Mixin 0.8.x has no
     * exception-exit injection point, so a render method that throws between them never runs its
     * {@link #pop()} or {@link #popTextInput()}. The stale {@link TextKind} then misclassifies every
     * later call, and a text-input depth above zero makes {@code RenderedTextBridge} return every
     * string untranslated for the rest of the session with nothing in the log. Called once per
     * client tick, before the frame is rendered.
     */
    public static void reset() {
        KINDS.remove();
        TEXT_INPUT_DEPTH.remove();
    }
}
