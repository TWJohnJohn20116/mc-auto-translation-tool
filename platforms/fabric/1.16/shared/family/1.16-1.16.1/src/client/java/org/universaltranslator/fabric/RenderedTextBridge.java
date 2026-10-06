package org.universaltranslator.fabric;

import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.StringRenderable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;
import org.universaltranslator.core.TranslationStyleRuns;

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
        Text styled = translateStyledSiblings(text);
        if (styled != null) {
            return styled;
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

    /**
     * Rebuilds the translation as one child per source sibling so every server-provided style
     * survives; flattening the component into a single literal keeps only the first style.
     * Returns {@code null} when the source is not a flat sibling list or carries a single
     * style, so the caller keeps the ordinary path.
     */
    private static Text translateStyledSiblings(Text text) {
        List<Text> siblings = text.getSiblings();
        if (siblings.isEmpty()) {
            return null;
        }
        List<String> texts = new ArrayList<String>(siblings.size() + 1);
        List<Object> styleKeys = new ArrayList<Object>(siblings.size() + 1);
        List<Style> styles = new ArrayList<Style>(siblings.size() + 1);
        boolean sourceHasColor = text.getStyle().getColor() != null;
        int siblingLength = 0;
        for (Text sibling : siblings) {
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
                sourceHasColor, FabricTranslationRuntime.translatedTextColor());
        MutableText rebuilt = new LiteralText("");
        rebuilt.setStyle(text.getStyle());
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(new LiteralText(translateRaw(run.text()))
                    .setStyle(applyColor(styles.get(run.sourceIndex()), color)));
        }
        return rebuilt;
    }

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

    private static Style translatedStyle(Style original) {
        return applyColor(original, TranslationStyleRuns.resolveTranslatedColor(
                original.getColor() != null,
                FabricTranslationRuntime.translatedTextColor()));
    }
}
