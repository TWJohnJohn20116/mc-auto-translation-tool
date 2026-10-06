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
import org.universaltranslator.core.TranslationStyleRuns;

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
        ITextComponent styled = translateStyledSiblings(text);
        if (styled != null) {
            return styled;
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
        final boolean[] multiStyle = new boolean[]{false};
        text.accept((index, style, codePoint) -> {
            if (original.length() == 0) {
                firstStyle.set(style);
            } else if (!multiStyle[0] && !style.equals(firstStyle.get())) {
                multiStyle[0] = true;
            }
            original.appendCodePoint(codePoint);
            return true;
        });
        if (multiStyle[0]) {
            // Flattening would keep only the first segment's colour and drop bold, italic and
            // click events from later segments, so rebuild the line run by run instead.
            IReorderingProcessor rebuilt = rebuildStyledRuns(text);
            if (rebuilt != null) {
                return rebuilt;
            }
        }
        String translated = translateRaw(original.toString());
        if (original.toString().equals(translated)) {
            return text;
        }
        IFormattableTextComponent replacement = new StringTextComponent(translated)
                .setStyle(translatedStyle(firstStyle.get()));
        return replacement.getVisualOrderText();
    }

    /** Rebuilds a multi-style ordered line as one child per style run, or null when it cannot. */
    private static IReorderingProcessor rebuildStyledRuns(IReorderingProcessor text) {
        List<String> texts = new ArrayList<String>();
        List<Object> styleKeys = new ArrayList<Object>();
        List<Style> styles = new ArrayList<Style>();
        StringBuilder current = new StringBuilder();
        AtomicReference<Style> currentStyle = new AtomicReference<Style>();
        text.accept((index, style, codePoint) -> {
            if (currentStyle.get() == null || !style.equals(currentStyle.get())) {
                addRun(texts, styleKeys, styles, current, currentStyle.get());
                currentStyle.set(style);
            }
            current.appendCodePoint(codePoint);
            return true;
        });
        addRun(texts, styleKeys, styles, current, currentStyle.get());
        List<TranslationStyleRuns.Run> runs = TranslationStyleRuns.mergeAdjacent(texts, styleKeys);
        if (!TranslationStyleRuns.shouldRebuildRuns(runs)) {
            return null;
        }
        boolean sourceHasColor = false;
        for (Style style : styles) {
            sourceHasColor |= style.getColor() != null;
        }
        TranslationTextColor color = TranslationStyleRuns.resolveTranslatedColor(
                sourceHasColor, ForgeTranslationRuntime.translatedTextColor());
        IFormattableTextComponent rebuilt = new StringTextComponent("");
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(new StringTextComponent(translateRaw(run.text()))
                    .setStyle(applyColor(styles.get(run.sourceIndex()), color)));
        }
        return rebuilt.getVisualOrderText();
    }

    private static void addRun(
            List<String> texts,
            List<Object> styleKeys,
            List<Style> styles,
            StringBuilder current,
            Style style
    ) {
        if (current.length() == 0) {
            return;
        }
        texts.add(current.toString());
        styleKeys.add(style);
        styles.add(style);
        current.setLength(0);
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

    /**
     * Rebuilds the translation as one child per source sibling so every server-provided style
     * survives; flattening the component into a single literal keeps only the first style.
     * Returns {@code null} when the source is not a flat sibling list or carries a single
     * style, so the caller keeps the ordinary path.
     */
    private static ITextComponent translateStyledSiblings(ITextComponent text) {
        List<ITextComponent> siblings = text.getSiblings();
        if (siblings.isEmpty()) {
            return null;
        }
        List<String> texts = new ArrayList<String>(siblings.size() + 1);
        List<Object> styleKeys = new ArrayList<Object>(siblings.size() + 1);
        List<Style> styles = new ArrayList<Style>(siblings.size() + 1);
        boolean sourceHasColor = text.getStyle().getColor() != null;
        int siblingLength = 0;
        for (ITextComponent sibling : siblings) {
            if (!sibling.getSiblings().isEmpty()) {
                return null;
            }
            String value = sibling.getString();
            texts.add(value);
            styleKeys.add(sibling.getStyle());
            styles.add(sibling.getStyle());
            siblingLength += value.length();
            sourceHasColor |= sibling.getStyle().getColor() != null;
        }
        String full = text.getString();
        if (full.length() < siblingLength) {
            return null;
        }
        // Text the root renders before its siblings keeps the root's own style.
        String own = full.substring(0, full.length() - siblingLength);
        if (!own.isEmpty()) {
            texts.add(0, own);
            styleKeys.add(0, text.getStyle());
            styles.add(0, text.getStyle());
        }
        List<TranslationStyleRuns.Run> runs = TranslationStyleRuns.mergeAdjacent(texts, styleKeys);
        if (!TranslationStyleRuns.shouldRebuildRuns(runs)) {
            return null;
        }
        TranslationTextColor color = TranslationStyleRuns.resolveTranslatedColor(
                sourceHasColor, ForgeTranslationRuntime.translatedTextColor());
        IFormattableTextComponent rebuilt = new StringTextComponent("");
        rebuilt.setStyle(text.getStyle());
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(new StringTextComponent(translateRaw(run.text()))
                    .setStyle(applyColor(styles.get(run.sourceIndex()), color)));
        }
        return rebuilt;
    }

    private static Style applyColor(Style original, TranslationTextColor color) {
        if (color == null) {
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

    private static Style translatedStyle(Style original) {
        return applyColor(original, TranslationStyleRuns.resolveTranslatedColor(
                original.getColor() != null,
                ForgeTranslationRuntime.translatedTextColor()));
    }
}
