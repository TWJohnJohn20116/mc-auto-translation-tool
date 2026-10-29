package org.universaltranslator.fabric;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.FormattedCharSequence;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;
import org.universaltranslator.core.TranslationStyleRuns;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.WeakIdentitySet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RenderedTextBridge {
    // One-shot diagnostics carried over from the 1.19–1.21 copy (shared/rendered-common): when a
    // player reports "the item tooltip is not translated", the debug log has to answer whether the
    // producer hook ran at all and whether the list actually changed. Logged once per session and
    // only while debug logging is on, so an ordinary hover costs one boolean read.
    private static final AtomicBoolean ITEM_TOOLTIP_REACHED = new AtomicBoolean();
    private static final AtomicBoolean ITEM_TOOLTIP_APPLIED = new AtomicBoolean();
    /**
     * Tooltip lists this class already produced.
     *
     * <p>Two hooks are mounted on the same line of work: {@code Screen.getTooltipFromItem}
     * produces the list and the GUI graphics extractor consumes it. Both are injected, so without
     * a mark the consumer receives the already translated list and translates it again — a second
     * provider request per hover, and with the English-only filter off the Chinese result is
     * translated a second time and can change between frames.
     *
     * <p>The mark is written where the list is produced ({@link #translateItemTooltip}), not where
     * it is consumed: the consumer cannot tell a list it has already seen from a fresh one, so a
     * consumer-side mark left every produced list translating a second time.
     */
    private static final WeakIdentitySet HANDLED_TOOLTIPS = new WeakIdentitySet();

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
        return Component.literal(translated).setStyle(translatedStyle(text.getStyle()));
    }

    public static FormattedCharSequence translate(FormattedCharSequence text) {
        if (text == null) {
            return null;
        }
        // This is the measurement path: Font.width(...) reaches it hundreds to thousands of times
        // per frame, so the "is there anything to translate" answer has to be free. Walking the
        // sequence to build the original string before asking costs a StringBuilder, an
        // AtomicReference, a boolean[] and two Strings per call even when translation is off.
        if (!FabricTranslationRuntime.renderTranslationActive()) {
            return text;
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
            FormattedCharSequence rebuilt = rebuildStyledRuns(text);
            if (rebuilt != null) {
                return rebuilt;
            }
        }
        String translated = translateRaw(original.toString());
        if (original.toString().equals(translated)) {
            return text;
        }
        MutableComponent replacement = Component.literal(translated)
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
                sourceHasColor, FabricTranslationRuntime.translatedTextColor());
        MutableComponent rebuilt = Component.literal("");
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(Component.literal(translateRaw(run.text()))
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
        if (!FabricTranslationRuntime.renderTranslationActive()) {
            return text;
        }
        String original = text.getString();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        return FormattedText.of(translated, translatedStyle(Style.EMPTY));
    }

    /** Translates item names and lore before GuiGraphicsExtractor creates tooltip components. */
    public static List<Component> translateTooltip(List<Component> lines) {
        if (HANDLED_TOOLTIPS.contains(lines)) {
            // Produced by translateItemTooltip a moment ago; the answer is already in this list.
            return lines;
        }
        // Deliberately no mark here: this is the consumer, and a list it translates itself is the
        // list it is about to draw, so a mark would only record one entry per frame without ever
        // preventing a repeat. The producer owns the mark.
        return translateTooltip(lines, false);
    }

    /** Canonical Screen.getTooltipFromItem hook for inventories and containers. */
    public static List<Component> translateItemTooltip(List<Component> lines) {
        if (ITEM_TOOLTIP_REACHED.compareAndSet(false, true)) {
            DebugLog.global().log("item-tooltip", "producer", "reached");
        }
        List<Component> result = translateTooltip(lines, true);
        if (result != lines) {
            // Write the mark here, at the producing end: the consumer (setTooltipForNextFrame)
            // receives this exact list next and must recognise it instead of translating the
            // already translated lines a second time.
            HANDLED_TOOLTIPS.add(result);
        }
        return result;
    }

    private static List<Component> translateTooltip(List<Component> lines, boolean itemTooltip) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<String> originals = new ArrayList<String>(lines.size());
        for (Component line : lines) {
            originals.add(line == null ? "" : line.getString());
        }
        List<String> translatedLines = FabricTranslationRuntime.translateLinesForRender(
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
                        Component.literal(translated).setStyle(translatedStyle(line.getStyle())));
            }
        }
        if (itemTooltip && replacement != null
                && ITEM_TOOLTIP_APPLIED.compareAndSet(false, true)) {
            // Logged once per session, and only when debug logging is on.
            DebugLog.global().log("item-tooltip", "result", "translated");
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
                sourceHasColor, FabricTranslationRuntime.translatedTextColor());
        MutableComponent rebuilt = Component.literal("");
        rebuilt.setStyle(text.getStyle());
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(Component.literal(translateRaw(run.text()))
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
     *
     * <p>Each node contributes only its own contents. Reading {@code getString()} instead copies
     * the whole subtree at every level, which costs O(nodes × subtree size) — quadratic on a tree
     * and about 90 fresh strings for a 30-node, three-level chat line, on every frame it renders.
     * The return value is the number of characters appended for this subtree, which keeps the walk
     * linear for a caller that needs the length.</p>
     */
    private static int collectStyledLeaves(
            Component node,
            List<String> texts,
            List<Object> styleKeys,
            List<Style> styles
    ) {
        int own = 0;
        if (node.getContents() instanceof PlainTextContents) {
            String content = ((PlainTextContents) node.getContents()).text();
            if (!content.isEmpty()) {
                texts.add(content);
                styleKeys.add(node.getStyle());
                styles.add(node.getStyle());
                own = content.length();
            }
        }
        for (Component child : node.getSiblings()) {
            own += collectStyledLeaves(child, texts, styleKeys, styles);
        }
        return own;
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
                FabricTranslationRuntime.translatedTextColor()));
    }
}
