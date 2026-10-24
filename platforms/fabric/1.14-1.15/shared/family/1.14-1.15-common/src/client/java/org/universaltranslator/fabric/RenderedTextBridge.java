package org.universaltranslator.fabric;

import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationStyleRuns;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;

import java.util.ArrayList;
import java.util.List;

public final class RenderedTextBridge {
    private RenderedTextBridge() {
    }

    public static String translate(String text) {
        String translated = translateRaw(text);
        if (text == null || text.equals(translated)) {
            return text;
        }
        return TranslationTextStyling.applyTranslatedStyle(
                text, translated, FabricTranslationRuntime.translatedTextColor());
    }

    public static Text translate(Text text) {
        if (text == null) {
            return null;
        }
        String original = text.getString();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        // Keeping the original style is what preserves rarity colours and bold/italic runs; building
        // a bare LiteralText flattened every translated component to plain white.
        return new LiteralText(translated).setStyle(translatedStyle(text.getStyle()));
    }

    public static List<Text> translateTooltip(List<Text> lines) {
        return translateTooltip(lines, false);
    }

    public static List<Text> translateItemTooltip(List<Text> lines) {
        return translateTooltip(lines, true);
    }

    private static List<Text> translateTooltip(List<Text> lines, boolean itemTooltip) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<String> originals = new ArrayList<String>(lines.size());
        for (Text line : lines) {
            originals.add(line == null ? "" : line.getString());
        }
        List<String> translatedLines = FabricTranslationRuntime.translateLinesForRender(
                originals, TextKind.TOOLTIP);
        List<Text> replacement = null;
        for (int index = 0; index < lines.size(); index++) {
            Text line = lines.get(index);
            if (line == null) {
                continue;
            }
            String original = originals.get(index);
            String translated = translatedLines.get(index);
            if (!original.equals(translated)) {
                if (replacement == null) {
                    replacement = new ArrayList<Text>(lines);
                }
                replacement.set(index,
                        new LiteralText(translated).setStyle(translatedStyle(line.getStyle())));
            }
        }
        return replacement == null ? lines : replacement;
    }

    /** Applies the configured translated-text colour to a style, leaving every other attribute. */
    private static Style applyColor(Style original, TranslationTextColor color) {
        if (color == null) {
            return original;
        }
        switch (color) {
            case GREEN: return original.withColor(Formatting.GREEN);
            case GOLD: return original.withColor(Formatting.GOLD);
            case LIGHT_PURPLE: return original.withColor(Formatting.LIGHT_PURPLE);
            case YELLOW: return original.withColor(Formatting.YELLOW);
            case WHITE: return original.withColor(Formatting.WHITE);
            case AQUA: return original.withColor(Formatting.AQUA);
            default: return original;
        }
    }

    /**
     * The style a translated component keeps: everything the original carried (colour, bold, italic,
     * click and hover events), with the translated-text colour applied only when the original had no
     * colour of its own. {@code ORIGINAL} therefore means "leave the style untouched".
     */
    private static Style translatedStyle(Style original) {
        return applyColor(original, TranslationStyleRuns.resolveTranslatedColor(
                original.getColor() != null,
                FabricTranslationRuntime.translatedTextColor()));
    }

    private static String translateRaw(String text) {
        if (TranslationRenderContext.isTextInput()) {
            return text;
        }
        return FabricTranslationRuntime.translateForRender(
                text, TranslationRenderContext.current());
    }
}
