package org.universaltranslator.fabric;

import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationTextStyling;

import java.util.ArrayList;
import java.util.List;

/** Public Mixin injection target. Keep this signature stable across 1.8.9-1.12.2. */
public final class RenderedTextBridge {
    private RenderedTextBridge() {
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
        return TranslationTextStyling.applyTranslatedStyle(
                text, translated, FabricTranslationRuntime.translatedTextColor());
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
            if (original != null && !original.equals(translated)) {
                translated = TranslationTextStyling.applyTranslatedStyle(
                        original, translated, FabricTranslationRuntime.translatedTextColor());
            }
            if (original == null ? translated != null : !original.equals(translated)) {
                if (replacement == null) {
                    replacement = new ArrayList<String>(lines);
                }
                replacement.set(index, translated);
            }
        }
        return replacement == null ? lines : replacement;
    }
}
