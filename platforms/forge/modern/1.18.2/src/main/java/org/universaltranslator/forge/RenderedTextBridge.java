package org.universaltranslator.forge;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;
import org.universaltranslator.core.TranslationStyleRuns;
import org.universaltranslator.core.TextKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class RenderedTextBridge {
    // Same-line memo in front of the ordered-line rebuild (P2-076). The chat overlay and the HUD
    // redraw identical FormattedCharSequence instances every frame, and the rebuild below costs a
    // full accept() walk plus one StringBuilder, one AtomicReference and three ArrayLists per line.
    // The memo is valid for exactly one client tick, which keeps a freshly finished translation
    // from being deferred by more than 50 ms.
    private static volatile FormattedCharSequence orderMemoInput;
    private static volatile FormattedCharSequence orderMemoOutput;
    private static volatile int orderMemoEpoch;

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

    public static Component translate(Component text) {
        if (text == null) {
            return null;
        }
        // A component with no children renders exactly its own literal under its own style, so the
        // run walk below would collect a single run and decline to rebuild it every time. Skipping
        // it keeps an ordinary label at one string copy and one cache lookup per frame.
        Component styled = text.getSiblings().isEmpty() ? null : translateStyledSiblings(text);
        if (styled != null) {
            return styled;
        }
        String original = text.getString();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        return new TextComponent(translated).setStyle(translatedStyle(text.getStyle()));
    }

    public static FormattedCharSequence translate(FormattedCharSequence text) {
        if (text == null) {
            return null;
        }
        int epoch = ForgeTranslationRuntime.currentRenderEpoch();
        FormattedCharSequence memoised = orderMemoOutput;
        if (memoised != null && orderMemoInput == text && orderMemoEpoch == epoch) {
            return memoised;
        }
        FormattedCharSequence result = translateOrdered(text);
        orderMemoInput = text;
        orderMemoOutput = result;
        orderMemoEpoch = epoch;
        return result;
    }

    private static FormattedCharSequence translateOrdered(FormattedCharSequence text) {
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
            FormattedCharSequence rebuilt = rebuildStyledRuns(text);
            if (rebuilt != null) {
                return rebuilt;
            }
        }
        String translated = translateRaw(original.toString());
        if (original.toString().equals(translated)) {
            return text;
        }
        MutableComponent replacement = new TextComponent(translated)
                .setStyle(translatedStyle(firstStyle.get()));
        return replacement.getVisualOrderText();
    }

    /** Rebuilds a multi-style ordered line as one child per style run, or null when it cannot. */
    private static FormattedCharSequence rebuildStyledRuns(FormattedCharSequence text) {
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
        MutableComponent rebuilt = new TextComponent("");
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(new TextComponent(translateRaw(run.text()))
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

    public static FormattedText translate(FormattedText text) {
        if (text == null) {
            return null;
        }
        if (text instanceof Component) {
            return translate((Component) text);
        }
        String original = text.getString();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        // FormattedText is not necessarily a Component, but every implementation reports the styles
        // its visible text renders with. Keeping the first one stops a translated hover or tooltip
        // line from turning plain white, which is what passing Style.EMPTY used to do.
        return new TextComponent(translated).setStyle(translatedStyle(firstStyleOf(text)));
    }

    /** First non-empty style {@code text} renders with, or {@link Style#EMPTY} when it has none. */
    private static Style firstStyleOf(FormattedText text) {
        final Style[] found = new Style[]{Style.EMPTY};
        final boolean[] taken = new boolean[]{false};
        Optional<Object> ignored = text.visit((style, contents) -> {
            if (!taken[0] && !contents.isEmpty()) {
                found[0] = style;
                taken[0] = true;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    /** Translates item names and lore before DrawContext creates tooltip components. */
    public static List<Component> translateTooltip(List<Component> lines) {
        return translateLines(lines);
    }

    /** Canonical Screen.getTooltipFromItem hook for inventories and containers. */
    public static List<Component> translateItemTooltip(List<Component> lines) {
        // The two one-shot AtomicBoolean "we reached / we applied the tooltip hook" flags that used
        // to sit here only fed a first-occurrence debug line; ScreenMixin now reports the optional
        // hook through ForgeMixinDiagnostics, which costs one volatile read instead of a CAS on
        // every hovered item.
        return translateLines(lines);
    }

    private static List<Component> translateLines(List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<String> originals = new ArrayList<String>(lines.size());
        for (Component line : lines) {
            originals.add(line == null ? "" : line.getString());
        }
        List<String> translatedLines = ForgeTranslationRuntime.translateLinesForRender(
                originals, TextKind.TOOLTIP);
        List<Component> replacement = null;
        for (int index = 0; index < lines.size(); index++) {
            Component line = lines.get(index);
            if (line == null) {
                continue;
            }
            String original = originals.get(index);
            String translated = translatedLines.get(index);
            if (!original.equals(translated)) {
                if (replacement == null) {
                    replacement = new ArrayList<Component>(lines);
                }
                replacement.set(index,
                        new TextComponent(translated).setStyle(translatedStyle(line.getStyle())));
            }
        }
        return replacement == null ? lines : replacement;
    }

    private static String translateRaw(String text) {
        if (TranslationRenderContext.isTextInput()) {
            return text;
        }
        return ForgeTranslationRuntime.translateForRender(
                text, TranslationRenderContext.current());
    }

    /**
     * Rebuilds the translation as one child per style the source's visible text renders with, so
     * every server-provided style survives; flattening the component into a single literal keeps
     * only the style of the node it was flattened from. Returns {@code null} when the visible text
     * already renders with the root's own style, so the caller keeps the ordinary path.
     */
    private static Component translateStyledSiblings(Component text) {
        List<String> texts = new ArrayList<String>();
        List<Object> styleKeys = new ArrayList<Object>();
        List<Style> styles = new ArrayList<Style>();
        collectStyledLeaves(text, texts, styleKeys, styles);
        if (texts.isEmpty()) {
            return null;
        }
        boolean sourceHasColor = text.getStyle().getColor() != null;
        for (Style style : styles) {
            sourceHasColor |= style.getColor() != null;
        }
        List<TranslationStyleRuns.Run> runs = TranslationStyleRuns.mergeAdjacent(texts, styleKeys);
        if (!TranslationStyleRuns.shouldRebuildRuns(runs, text.getStyle())) {
            return null;
        }
        TranslationTextColor color = TranslationStyleRuns.resolveTranslatedColor(
                sourceHasColor, ForgeTranslationRuntime.translatedTextColor());
        MutableComponent rebuilt = new TextComponent("");
        rebuilt.setStyle(text.getStyle());
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(new TextComponent(translateRaw(run.text()))
                    .setStyle(applyColor(styles.get(run.sourceIndex()), color)));
        }
        return rebuilt;
    }

    /**
     * Collects the visible text of {@code text} together with the style of the node that carries
     * it, descending into nested children.
     *
     * <p>Team prefixes and suffixes and most components a server builds arrive as nested trees, so
     * reading only the direct children left the caller with the root style alone and dropped the
     * colour the line is actually drawn with. Every leaf keeps its own style here; the caller
     * re-attaches the root style and Minecraft merges the two when it renders the component.</p>
     */
    private static void collectStyledLeaves(
            Component node,
            List<String> texts,
            List<Object> styleKeys,
            List<Style> styles
    ) {
        List<Component> children = node.getSiblings();
        String full = node.getString();
        int childLength = 0;
        for (Component child : children) {
            childLength += child.getString().length();
        }
        // Text a node renders before its children keeps that node's own style.
        if (full.length() > childLength) {
            String own = full.substring(0, full.length() - childLength);
            if (!own.isEmpty()) {
                texts.add(own);
                styleKeys.add(node.getStyle());
                styles.add(node.getStyle());
            }
        }
        for (Component child : children) {
            collectStyledLeaves(child, texts, styleKeys, styles);
        }
    }

    private static Style applyColor(Style original, TranslationTextColor color) {
        if (color == null) {
            return original;
        }
        switch (color) {
            case GREEN: return original.withColor(ChatFormatting.GREEN);
            case GOLD: return original.withColor(ChatFormatting.GOLD);
            case LIGHT_PURPLE: return original.withColor(ChatFormatting.LIGHT_PURPLE);
            case YELLOW: return original.withColor(ChatFormatting.YELLOW);
            case WHITE: return original.withColor(ChatFormatting.WHITE);
            case AQUA: return original.withColor(ChatFormatting.AQUA);
            default: return original;
        }
    }

    private static Style translatedStyle(Style original) {
        return applyColor(original, TranslationStyleRuns.resolveTranslatedColor(
                original.getColor() != null,
                ForgeTranslationRuntime.translatedTextColor()));
    }
}
