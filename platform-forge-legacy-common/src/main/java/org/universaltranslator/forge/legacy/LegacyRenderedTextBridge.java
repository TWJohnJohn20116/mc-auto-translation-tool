package org.universaltranslator.forge.legacy;

import org.universaltranslator.core.TranslationTextStyling;
import org.universaltranslator.core.TextKind;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public bytecode injection target. Keep this signature stable across legacy versions. */
public final class LegacyRenderedTextBridge {
    private static final AtomicBoolean ITEM_TOOLTIP_REACHED = new AtomicBoolean();
    private static final AtomicBoolean ITEM_TOOLTIP_APPLIED = new AtomicBoolean();

    private LegacyRenderedTextBridge() {
    }

    public static String translate(String text) {
        if (LegacyRenderContext.isTextInput()) {
            return text;
        }
        return translate(text, LegacyRenderContext.current());
    }

    private static String translate(String text, TextKind kind) {
        if (text == null) {
            return null;
        }
        String translated = LegacyTranslationRuntime.translate(text, kind);
        if (text.equals(translated)) {
            return text;
        }
        return TranslationTextStyling.applyLegacyColor(
                translated, LegacyTranslationRuntime.translatedTextColor());
    }

    /** High-level tooltip hook used by both 1.8.9 and 1.12.2. */
    public static List<String> translateTooltipLines(List<String> lines) {
        return translateTooltipLines(lines, false);
    }

    /** Canonical ItemStack tooltip producer hook used by inventory and container screens. */
    public static List<String> translateItemTooltipLines(List<String> lines) {
        if (ITEM_TOOLTIP_REACHED.compareAndSet(false, true)) {
            System.out.println("[MC Auto Translation Tool] Item tooltip producer reached");
        }
        return translateTooltipLines(lines, true);
    }

    private static List<String> translateTooltipLines(List<String> lines, boolean itemTooltip) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<String> translatedLines = LegacyTranslationRuntime.translateLines(
                lines, TextKind.TOOLTIP);
        List<String> replacement = null;
        for (int index = 0; index < lines.size(); index++) {
            String original = lines.get(index);
            String translated = translatedLines.get(index);
            if (original != null && !original.equals(translated)) {
                translated = TranslationTextStyling.applyLegacyColor(
                        translated, LegacyTranslationRuntime.translatedTextColor());
            }
            if (original == null ? translated != null : !original.equals(translated)) {
                if (replacement == null) {
                    replacement = new ArrayList<String>(lines);
                }
                replacement.set(index, translated);
            }
        }
        if (itemTooltip && replacement != null
                && ITEM_TOOLTIP_APPLIED.compareAndSet(false, true)) {
            System.out.println("[MC Auto Translation Tool] Item tooltip translation applied");
        }
        return replacement == null ? lines : replacement;
    }
}
