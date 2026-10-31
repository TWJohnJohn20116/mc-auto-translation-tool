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
    /**
     * Recently styled lines, cycling through a fixed ring.
     *
     * <p>{@code TranslationTextStyling.applyTranslatedStyle} scans the whole original line for a
     * legacy colour code and, once the player picks a translated colour, rebuilds the translated
     * line character by character into a fresh {@code String}. The same line is drawn once and
     * measured two to four times per frame, so that work used to be repeated for every call even
     * though nothing about the input had changed.
     *
     * <p>The key is everything the styling reads — original text, translated text and the
     * configured colour — so a hit is by construction the value a fresh call would return.
     */
    private static final int STYLE_MEMO_SIZE = 8;
    private static final StyledText[] STYLE_MEMO = new StyledText[STYLE_MEMO_SIZE];
    private static int styleMemoCursor;

    /** One memo entry: the inputs of a styling call and the string it produced. */
    private static final class StyledText {
        private final String original;
        private final String translated;
        private final TranslationTextColor color;
        private final String styled;

        private StyledText(String original, String translated, TranslationTextColor color,
                String styled) {
            this.original = original;
            this.translated = translated;
            this.color = color;
            this.styled = styled;
        }
    }

    private RenderedTextBridge() {
    }

    /**
     * Records that one injection of this generation really fired, once per session.
     *
     * <p>Every hook in this tree is declared {@code require = 0} so a descriptor that drifts between
     * Minecraft versions degrades one surface instead of failing the launch. That choice used to be
     * completely silent: nothing said which surfaces were alive, so "translation stopped working"
     * for one interface and "this version never hooked it" looked identical. The caller owns the
     * flag, so a repeat costs one atomic read.
     */
    public static void surface(String name, AtomicBoolean once) {
        if (once.compareAndSet(false, true)) {
            DebugLog.global().log("render-surface", "applied", name);
        }
    }

    public static String translate(String text) {
        if (text == null) {
            return null;
        }
        // The drawing and the measuring hook both arrive here for every string of every frame, so
        // the line this bridge answered a moment ago is answered again: one set lookup instead of
        // the whole guard chain.
        if (TranslationRenderContext.isTranslatedResult(text)
                || TranslationRenderContext.isUnchangedResult(text)) {
            return text;
        }
        String translated = translateRaw(text);
        if (text.equals(translated)) {
            TranslationRenderContext.rememberUnchanged(text);
            return text;
        }
        String styled = styledText(text, translated);
        // Both forms, because the two hooks only see the same string when no colour is applied.
        TranslationRenderContext.rememberTranslation(translated);
        TranslationRenderContext.rememberTranslation(styled);
        return styled;
    }

    /** Styles one already translated line, reusing the answer when the same line is redrawn. */
    private static String styledText(String original, String translated) {
        TranslationTextColor color = FabricTranslationRuntime.translatedTextColor();
        for (int index = 0; index < STYLE_MEMO_SIZE; index++) {
            StyledText entry = STYLE_MEMO[index];
            if (entry != null && entry.color == color
                    && (entry.translated == translated || entry.translated.equals(translated))
                    && (entry.original == original || entry.original.equals(original))) {
                return entry.styled;
            }
        }
        String styled = TranslationTextStyling.applyTranslatedStyle(original, translated, color);
        STYLE_MEMO[styleMemoCursor++ & (STYLE_MEMO_SIZE - 1)] =
                new StyledText(original, translated, color, styled);
        return styled;
    }

    public static Component translate(Component text) {
        if (text == null) {
            return null;
        }
        // The hooks call this for every component of every frame whether or not translation can
        // answer, so ask the flag before the style walk: with the master switch off the walk below
        // still cost three lists, a whole-tree visit and a getString() per line and then returned
        // the input unchanged.
        if (!FabricTranslationRuntime.renderTranslationActive()) {
            return text;
        }
        String original = text.getString();
        // The measuring hook is handed the component the drawing hook already answered.
        if (TranslationRenderContext.isTranslatedResult(original)
                || TranslationRenderContext.isUnchangedResult(original)) {
            return text;
        }
        // One lookup for the whole line, shared by the run rebuild and the fallthrough below: the
        // walk used to translate every run on its own, so a two-segment chat line sent two requests
        // and each half was translated without the other.
        String translated = translateRaw(original);
        // A component with no children renders exactly its own literal under its own style, so the
        // run walk below would collect a single run and decline to rebuild it every time. Skipping
        // it keeps an ordinary label at one string copy and one cache lookup per frame.
        Component styled = text.getSiblings().isEmpty()
                ? null : translateStyledSiblings(text, original, translated);
        if (styled != null) {
            return styled;
        }
        if (original.equals(translated)) {
            TranslationRenderContext.rememberUnchanged(original);
            return text;
        }
        TranslationRenderContext.rememberTranslation(translated);
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
        // One toString() for both the lookup and the "did anything change" comparison: the builder
        // caches nothing across calls, so asking twice allocated the same line twice per draw.
        String visible = original.toString();
        // The drawing and the measuring hook are handed the same line, so the second call already
        // has the answer the first one produced.
        if (TranslationRenderContext.isTranslatedResult(visible)
                || TranslationRenderContext.isUnchangedResult(visible)) {
            return text;
        }
        // One lookup for the whole line, shared by the run rebuild and the fallthrough below: the
        // rebuild used to translate every run itself, so a two-segment line sent two requests and
        // each half was translated without the other.
        String translated = translateRaw(visible);
        if (multiStyle[0]) {
            // Flattening would keep only the first segment's colour and drop bold, italic and
            // click events from later segments, so rebuild the line run by run instead.
            FormattedCharSequence rebuilt = rebuildStyledRuns(text, visible, translated);
            if (rebuilt != null) {
                return rebuilt;
            }
        }
        if (visible.equals(translated)) {
            TranslationRenderContext.rememberUnchanged(visible);
            return text;
        }
        MutableComponent replacement = Component.literal(translated)
                .setStyle(translatedStyle(firstStyle.get()));
        TranslationRenderContext.rememberTranslation(translated);
        return replacement.getVisualOrderText();
    }

    /**
     * Rebuilds a multi-style ordered line from the caller's one translation, carrying the style of
     * the run its visible text starts with, or null when it cannot.
     *
     * <p>The line is translated once, by the caller: a run boundary is a style change, not a
     * sentence boundary, and per-run translation sent one request per segment with neither half
     * carrying the context the other provided.
     */
    private static FormattedCharSequence rebuildStyledRuns(
            FormattedCharSequence text, String visible, String translated
    ) {
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
        // runs indexes into texts; the style list is aligned by construction, but a merge rule
        // change in translator-core must not turn a misalignment into an exception thrown from the
        // middle of a draw and lose the whole frame.
        int firstRun = runs.get(0).sourceIndex();
        if (firstRun < 0 || firstRun >= styles.size()) {
            return null;
        }
        if (visible.equals(translated)) {
            return null;
        }
        MutableComponent rebuilt = Component.literal(translated)
                .setStyle(applyColor(styles.get(firstRun), color));
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
        // Same paired hooks as the two overloads above: the second call is handed what the first one
        // already translated.
        if (TranslationRenderContext.isTranslatedResult(original)
                || TranslationRenderContext.isUnchangedResult(original)) {
            return text;
        }
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            TranslationRenderContext.rememberUnchanged(original);
            return text;
        }
        FormattedText styled = FormattedText.of(translated, translatedStyle(Style.EMPTY));
        TranslationRenderContext.rememberTranslation(translated);
        return styled;
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
        // A hovered item reaches this once per frame. With the master switch off every line comes
        // back unchanged, so the getString() per line and the batch lookup are pure cost.
        if (!FabricTranslationRuntime.renderTranslationActive()) {
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
     * Rebuilds the line from the caller's one translation, carrying the style of the run its visible
     * text starts with: server-provided styles cannot be mapped segment by segment onto a translation
     * that no longer aligns with them. Returns {@code null} when the visible text already renders
     * with the root's own style, or when it came back unchanged, so the caller keeps the ordinary
     * path.
     */
    private static Component translateStyledSiblings(Component text, String visible, String translated) {
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
        // See rebuildStyledRuns: runs indexes into texts, never into styles.
        int firstRun = runs.get(0).sourceIndex();
        if (firstRun < 0 || firstRun >= styles.size()) {
            return null;
        }
        if (visible.equals(translated)) {
            return null;
        }
        MutableComponent rebuilt = Component.literal(translated)
                .setStyle(applyColor(styles.get(firstRun), color));
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
