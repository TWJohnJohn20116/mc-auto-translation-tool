package org.universaltranslator.fabric;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public Mixin injection target. Keep this signature stable across 1.8.9-1.12.2. */
public final class RenderedTextBridge {
    /**
     * The last line styled and the answer it produced.
     *
     * <p>{@code TranslationTextStyling.applyTranslatedStyle} scans the whole original line for a
     * legacy colour code and rebuilds the translated line into a fresh {@code String} once a
     * translated colour is configured. The same line is measured and then drawn, so without a memo
     * that work ran twice per line per frame; the single slot catches exactly that repeat.
     */
    private static String memoOriginal;
    private static String memoTranslated;
    private static TranslationTextColor memoColor;
    private static String memoStyled;

    private RenderedTextBridge() {
    }

    /**
     * Records that one injection of this generation really fired, once per session.
     *
     * <p>Every hook in this tree is declared {@code require = 0} so a descriptor that drifts between
     * Minecraft versions degrades one surface instead of failing the launch. That choice used to be
     * completely silent: nothing said which surfaces were alive, so "translation stopped working"
     * and "this version never hooked that entry point" looked identical.
     */
    public static void surface(String name, AtomicBoolean once) {
        if (once.compareAndSet(false, true)) {
            DebugLog.global().log("render-surface", "applied", name);
        }
    }

    public static String translate(String text) {
        if (TranslationRenderContext.isTextInput()) {
            return text;
        }
        // The legacy draw and width hooks both arrive here for the same line of the same frame, so
        // the second call already has its answer: the paired TranslationRenderContext kept these two
        // sets from the start and this bridge never wrote to either of them, which is why the tree
        // ran the whole guard chain and one lookup twice per line.
        if (answeredThisFrame(text)) {
            return text;
        }
        return translate(text, TranslationRenderContext.current());
    }

    private static String translate(String text, TextKind kind) {
        if (text == null) {
            return null;
        }
        String translated = FabricTranslationRuntime.translateForRender(text, kind);
        if (text.equals(translated)) {
            rememberUnchanged(text);
            return text;
        }
        String styled = styledText(text, translated);
        // Both forms, because the two hooks only see the same string when no colour is applied.
        rememberTranslated(translated);
        rememberTranslated(styled);
        return styled;
    }

    /**
     * The frame memo of the {@code TranslationRenderContext} this tree compiles against.
     *
     * <p>Reached reflectively on purpose: the 1.13 trees pair this bridge with their own copy of the
     * context, which has neither set, so a direct call would stop those two targets from compiling.
     * A tree without the memo keeps the previous behaviour — the whole pipeline twice per line —
     * and every other tree turns the second pass into one set lookup. Slots are
     * {@code isTranslatedResult}, {@code isUnchangedResult}, {@code rememberTranslation} and
     * {@code rememberUnchanged}, all taking and answering a {@code String}.
     */
    private static final java.lang.reflect.Method[] FRAME_MEMO = frameMemo();

    private static java.lang.reflect.Method[] frameMemo() {
        String[] names = {
                "isTranslatedResult", "isUnchangedResult", "rememberTranslation", "rememberUnchanged"};
        java.lang.reflect.Method[] methods = new java.lang.reflect.Method[names.length];
        try {
            for (int index = 0; index < names.length; index++) {
                java.lang.reflect.Method method = TranslationRenderContext.class
                        .getDeclaredMethod(names[index], String.class);
                method.setAccessible(true);
                methods[index] = method;
            }
        } catch (NoSuchMethodException | RuntimeException absent) {
            // No memo in this tree's context: every slot stays null and the three callers below
            // become no-ops instead of a build failure.
        }
        return methods;
    }

    /** True when this frame already answered {@code text}, either way. */
    private static boolean answeredThisFrame(String text) {
        return memoResult(FRAME_MEMO[0], text) || memoResult(FRAME_MEMO[1], text);
    }

    private static void rememberTranslated(String translated) {
        memoStore(FRAME_MEMO[2], translated);
    }

    private static void rememberUnchanged(String original) {
        memoStore(FRAME_MEMO[3], original);
    }

    private static boolean memoResult(java.lang.reflect.Method method, String value) {
        if (method == null || value == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(null, value));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void memoStore(java.lang.reflect.Method method, String value) {
        if (method == null || value == null) {
            return;
        }
        try {
            method.invoke(null, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A context that changed shape degrades to the previous behaviour, never to a broken
            // frame: the memo only ever removes a repeat, so losing it costs what it costs today.
        }
    }

    /** Styles one already translated line, reusing the answer when the same line is redrawn. */
    private static String styledText(String original, String translated) {
        TranslationTextColor color = FabricTranslationRuntime.translatedTextColor();
        String cached = memoStyled;
        if (cached != null && memoColor == color
                && (memoTranslated == translated
                || (memoTranslated != null && memoTranslated.equals(translated)))
                && (memoOriginal == original
                || (memoOriginal != null && memoOriginal.equals(original)))) {
            return cached;
        }
        String styled = TranslationTextStyling.applyTranslatedStyle(original, translated, color);
        memoOriginal = original;
        memoTranslated = translated;
        memoColor = color;
        memoStyled = styled;
        return styled;
    }

    public static List<String> translateTooltipLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<String> translatedLines = FabricTranslationRuntime.translateLinesForRender(
                lines, TextKind.TOOLTIP);
        List<String> replacement = null;
        for (int index = 0; index < lines.size(); index++) {
            String original = lines.get(index);
            String translated = translatedLines.get(index);
            if (original == null) {
                if (translated != null) {
                    if (replacement == null) {
                        replacement = new ArrayList<String>(lines);
                    }
                    replacement.set(index, translated);
                }
                continue;
            }
            if (original.equals(translated)) {
                continue;
            }
            // One colour lookup for the whole list, and one comparison per line: the previous body
            // asked for the colour inside the loop and compared the pair twice.
            String styled = styledText(original, translated);
            if (!styled.equals(original)) {
                if (replacement == null) {
                    replacement = new ArrayList<String>(lines);
                }
                replacement.set(index, styled);
            }
        }
        return replacement == null ? lines : replacement;
    }
}
