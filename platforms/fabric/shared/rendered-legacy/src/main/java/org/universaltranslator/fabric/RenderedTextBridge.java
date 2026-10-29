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
        return translate(text, TranslationRenderContext.current());
    }

    private static String translate(String text, TextKind kind) {
        if (text == null) {
            return null;
        }
        String translated = FabricTranslationRuntime.translateForRender(text, kind);
        if (text.equals(translated)) {
            return text;
        }
        return styledText(text, translated);
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
