package org.universaltranslator.forge;

import net.minecraft.util.IReorderingProcessor;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.ITextProperties;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class RenderedTextBridge {
    private RenderedTextBridge() {
    }

    public static String translate(String text) {
        String translated = translateRaw(text);
        if (text == null || text.equals(translated)) {
            return text;
        }
        return TranslationTextStyling.applyTranslatedStyle(
                text, translated, ForgeTranslationRuntime.translatedTextColor());
    }

    public static ITextComponent translate(ITextComponent text) {
        if (text == null) {
            return null;
        }
        String original = text.getString();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        return new StringTextComponent(translated).setStyle(translatedStyle(text.getStyle()));
    }

    public static IReorderingProcessor translate(IReorderingProcessor text) {
        if (text == null) {
            return null;
        }
        StringBuilder original = new StringBuilder();
        AtomicReference<Style> firstStyle = new AtomicReference<Style>(Style.EMPTY);
        text.accept((index, style, codePoint) -> {
            if (original.length() == 0) {
                firstStyle.set(style);
            }
            original.appendCodePoint(codePoint);
            return true;
        });
        String translated = translateRaw(original.toString());
        if (original.toString().equals(translated)) {
            return text;
        }
        IFormattableTextComponent replacement = new StringTextComponent(translated)
                .setStyle(translatedStyle(firstStyle.get()));
        return replacement.getVisualOrderText();
    }

    public static ITextProperties translate(ITextProperties text) {
        if (text == null) {
            return null;
        }
        if (text instanceof ITextComponent) {
            return translate((ITextComponent) text);
        }
        String original = text.getString();
        String translated = translateRaw(original);
        return original.equals(translated)
                ? text : new StringTextComponent(translated).setStyle(translatedStyle(Style.EMPTY));
    }

    public static List<ITextComponent> translateItemTooltip(List<ITextComponent> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<String> originals = new ArrayList<String>(lines.size());
        for (ITextComponent line : lines) {
            originals.add(line == null ? "" : line.getString());
        }
        List<String> translated = ForgeTranslationRuntime.translateLinesForRender(
                originals, TextKind.TOOLTIP);
        List<ITextComponent> result = null;
        for (int index = 0; index < lines.size(); index++) {
            ITextComponent line = lines.get(index);
            if (line != null && !originals.get(index).equals(translated.get(index))) {
                if (result == null) {
                    result = new ArrayList<ITextComponent>(lines);
                }
                result.set(index, new StringTextComponent(translated.get(index))
                        .setStyle(translatedStyle(line.getStyle())));
            }
        }
        return result == null ? lines : result;
    }

    private static String translateRaw(String text) {
        if (TranslationRenderContext.isTextInput()) {
            return text;
        }
        return ForgeTranslationRuntime.translateForRender(text, TranslationRenderContext.current());
    }

    private static Style translatedStyle(Style original) {
        TranslationTextColor color = ForgeTranslationRuntime.translatedTextColor();
        if (original.getColor() != null || color == null || !color.changesColor()) {
            return original;
        }
        switch (color) {
            case GREEN: return original.withColor(TextFormatting.GREEN);
            case GOLD: return original.withColor(TextFormatting.GOLD);
            case LIGHT_PURPLE: return original.withColor(TextFormatting.LIGHT_PURPLE);
            case YELLOW: return original.withColor(TextFormatting.YELLOW);
            case WHITE: return original.withColor(TextFormatting.WHITE);
            case AQUA: return original.withColor(TextFormatting.AQUA);
            default: return original;
        }
    }
}
