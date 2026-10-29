package org.universaltranslator.forge;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;
import org.universaltranslator.core.TranslationStyleRuns;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.WeakIdentitySet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RenderedTextBridge {
    private static final AtomicBoolean ITEM_TOOLTIP_REACHED = new AtomicBoolean();
    private static final AtomicBoolean ITEM_TOOLTIP_APPLIED = new AtomicBoolean();
    /**
     * Tooltip lists this class already produced.
     *
     * <p>Two hooks are mounted on the same line of work: {@code Screen.getTooltipFromItem}
     * produces the list and {@code GuiGraphics.renderTooltip} consumes it. Both are injected, so
     * without a mark the consumer receives the already translated list and translates it again —
     * a second provider request per hover, and with the English-only filter off the Chinese result
     * is translated a second time and can change between frames.
     */
    private static final WeakIdentitySet HANDLED_TOOLTIPS = new WeakIdentitySet();

    private RenderedTextBridge() {
    }

    public static String translate(String text) {
        // GuiGraphics.drawCenteredString measures through Font.width and then draws through
        // GuiGraphics.drawString, and both are hooked, so the second and third call receive the line
        // this method just returned. Answering from the frame's own output set keeps those repeats off
        // the guard chain and out of the provider.
        if (TranslationRenderContext.isTranslatedResult(text)) {
            return text;
        }
        String translated = translateRaw(text);
        if (text == null || text.equals(translated)) {
            return text;
        }
        String styled = TranslationTextStyling.applyTranslatedStyle(
                text, translated, ForgeTranslationRuntime.translatedTextColor());
        // Both forms: the measuring hook is handed what the drawing hook then receives, and the two
        // only match when no translated colour is applied.
        TranslationRenderContext.rememberTranslation(translated);
        TranslationRenderContext.rememberTranslation(styled);
        return styled;
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
        StringBuilder original = new StringBuilder();
        final Style[] firstStyle = new Style[]{Style.EMPTY};
        final boolean[] multiStyle = new boolean[]{false};
        text.accept((index, style, codePoint) -> {
            if (original.length() == 0) {
                firstStyle[0] = style;
            } else if (!multiStyle[0] && !style.equals(firstStyle[0])) {
                multiStyle[0] = true;
            }
            original.appendCodePoint(codePoint);
            return true;
        });
        if (multiStyle[0]) {
            // Flattening would keep only the first segment's colour and drop bold, italic and
            // click events from later segments, so rebuild the line run by run instead. Returning
            // the line untouched — as this used to — skipped translation for every server-built
            // chat line, because a chat line is multi-style as soon as a team prefix or a player
            // name carries a colour.
            FormattedCharSequence rebuilt = rebuildStyledRuns(text);
            if (rebuilt != null) {
                return rebuilt;
            }
        }
        // One copy of the whole line, not two: StringBuilder.toString() allocates, and the previous
        // version called it for the lookup and again for the comparison.
        String flat = original.toString();
        String translated = translateRaw(flat);
        if (flat.equals(translated)) {
            return text;
        }
        MutableComponent replacement = Component.literal(translated)
                .setStyle(translatedStyle(firstStyle[0]));
        return replacement.getVisualOrderText();
    }

    /**
     * Rebuilds a multi-style ordered line as one child per style run, or {@code null} when the walk
     * produced nothing to rebuild.
     *
     * <p>The same algorithm the 1.16.5 and 1.18.2 bridges use: split the visual order text into
     * runs of one style each, merge neighbours that share a style, translate each run separately
     * and re-attach its own style. {@code Style}, {@code MutableComponent} and
     * {@code FormattedCharSequence} have the same shape in 1.19.2 and later, so nothing about this
     * needed to differ when the three bridges were collapsed into this shared layer.
     */
    private static FormattedCharSequence rebuildStyledRuns(FormattedCharSequence text) {
        List<String> texts = new ArrayList<String>();
        List<Object> styleKeys = new ArrayList<Object>();
        List<Style> styles = new ArrayList<Style>();
        StringBuilder current = new StringBuilder();
        final Style[] currentStyle = new Style[]{null};
        text.accept((index, style, codePoint) -> {
            if (currentStyle[0] == null || !style.equals(currentStyle[0])) {
                addRun(texts, styleKeys, styles, current, currentStyle[0]);
                currentStyle[0] = style;
            }
            current.appendCodePoint(codePoint);
            return true;
        });
        addRun(texts, styleKeys, styles, current, currentStyle[0]);
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
        String original = text.getString();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        return Component.literal(translated).setStyle(translatedStyle(Style.EMPTY));
    }

    /** Translates item names and lore before DrawContext creates tooltip components. */
    public static List<Component> translateTooltip(List<Component> lines) {
        if (HANDLED_TOOLTIPS.contains(lines)) {
            // Produced by translateItemTooltip a moment ago; the answer is already in this list.
            return lines;
        }
        List<Component> result = translateTooltip(lines, false);
        if (result != lines) {
            HANDLED_TOOLTIPS.add(result);
        }
        return result;
    }

    /** Canonical Screen.getTooltipFromItem hook for inventories and containers. */
    public static List<Component> translateItemTooltip(List<Component> lines) {
        if (ITEM_TOOLTIP_REACHED.compareAndSet(false, true)) {
            DebugLog.global().log("item-tooltip", "producer", "reached");
        }
        return translateTooltip(lines, true);
    }

    private static List<Component> translateTooltip(List<Component> lines, boolean itemTooltip) {
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
                        Component.literal(translated).setStyle(translatedStyle(line.getStyle())));
            }
        }
        if (itemTooltip && replacement != null
                && ITEM_TOOLTIP_APPLIED.compareAndSet(false, true)) {
            DebugLog.global().log("item-tooltip", "result", "translated");
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
     */
    private static void collectStyledLeaves(
            Component node,
            List<String> texts,
            List<Object> styleKeys,
            List<Style> styles
    ) {
        collectStyledLeaves(node, node.getString(), texts, styleKeys, styles);
    }

    /**
     * Collects the leaves of {@code node}, reusing the string the caller already built for it.
     *
     * <p>Every node used to call {@code getString()} for itself and then {@code getString()} again
     * for each child just to measure it, so each level of a server-built chat line rebuilt the whole
     * subtree twice. The caller's string is passed in and each child's is computed once, which halves
     * the copies without changing the order or the styles the leaves are recorded with.
     */
    private static void collectStyledLeaves(
            Component node,
            String full,
            List<String> texts,
            List<Object> styleKeys,
            List<Style> styles
    ) {
        List<Component> children = node.getSiblings();
        int childLength = 0;
        String[] childTexts = null;
        if (!children.isEmpty()) {
            childTexts = new String[children.size()];
            for (int index = 0; index < children.size(); index++) {
                childTexts[index] = children.get(index).getString();
                childLength += childTexts[index].length();
            }
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
        if (childTexts == null) {
            return;
        }
        for (int index = 0; index < children.size(); index++) {
            collectStyledLeaves(children.get(index), childTexts[index], texts, styleKeys, styles);
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
