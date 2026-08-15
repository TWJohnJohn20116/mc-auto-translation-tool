package org.universaltranslator.fabric;

import net.minecraft.text.LiteralText;
import net.minecraft.text.StringRenderable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RenderedTextBridge {
    private static final AtomicBoolean ITEM_TOOLTIP_REACHED = new AtomicBoolean();
    private static final AtomicBoolean ITEM_TOOLTIP_APPLIED = new AtomicBoolean();

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
        return new LiteralText(translated).setStyle(translatedStyle(text.getStyle()));
    }

    public static StringRenderable translate(StringRenderable text) {
        if (text == null) {
            return null;
        }
        if (text instanceof Text) {
            return translate((Text) text);
        }
        String original = text.getString();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        return StringRenderable.styled(translated, translatedStyle(Style.EMPTY));
    }

    /** Translates item names and lore before DrawContext creates tooltip components. */
    public static List<Text> translateTooltip(List<Text> lines) {
        return translateTooltip(lines, false);
    }

    /** Canonical Screen.getTooltipFromItem hook for inventories and containers. */
    public static List<Text> translateItemTooltip(List<Text> lines) {
        if (ITEM_TOOLTIP_REACHED.compareAndSet(false, true)) {
            System.out.println("[MC Auto Translation Tool] Item tooltip producer reached");
        }
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
        if (itemTooltip && replacement != null
                && ITEM_TOOLTIP_APPLIED.compareAndSet(false, true)) {
            System.out.println("[MC Auto Translation Tool] Item tooltip translation applied");
        }
        return replacement == null ? lines : replacement;
    }

    private static String translateRaw(String text) {
        if (TranslationRenderContext.isTextInput()) {
            return text;
        }
        return FabricTranslationRuntime.translateForRender(
                text, TranslationRenderContext.current());
    }

    private static Style translatedStyle(Style original) {
        TranslationTextColor color = FabricTranslationRuntime.translatedTextColor();
        if (original.getColor() != null || color == null || !color.changesColor()) {
            return original;
        }
        switch (color) {
            case GREEN: return original.withColor(Formatting.GREEN);
            case GOLD: return original.withColor(Formatting.GOLD);
            case LIGHT_PURPLE: return original.withColor(Formatting.LIGHT_PURPLE);
            case YELLOW: return original.withColor(Formatting.YELLOW);
            case WHITE: return original.withColor(Formatting.WHITE);
            case AQUA: return original.withColor(Formatting.AQUA);
            case ORIGINAL:
            default: return original;
        }
    }
}
