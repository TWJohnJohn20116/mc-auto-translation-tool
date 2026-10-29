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
     * configured colour — so a hit is by construction the value a fresh call would return:
     * switching the colour, the target language or the provider all change at least one key. The
     * ring is written from the render thread only, and every entry is immutable, so a concurrent
     * read can only miss or return a complete older entry.
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

    /**
     * One flat copy of a {@code FormattedCharSequence}, reused by the render thread it belongs to.
     *
     * <p>Flattening used to allocate a {@code StringBuilder}, a {@code Style[]} and a
     * {@code boolean[]} for every drawn line; the builder is the only part that has to grow with
     * the text, so it is kept and reset instead. Nothing nested calls back into this method on the
     * same thread, so one buffer per thread is enough.
     */
    private static final class Flattened {
        private final StringBuilder text = new StringBuilder();
        private Style first = Style.EMPTY;
        private boolean multiStyle;

        private StringBuilder reset() {
            text.setLength(0);
            first = Style.EMPTY;
            multiStyle = false;
            return text;
        }
    }

    private static final ThreadLocal<Flattened> FLATTENED = new ThreadLocal<Flattened>() {
        @Override
        protected Flattened initialValue() {
            return new Flattened();
        }
    };

    /**
     * The three per-run lists and the run builder, reused per thread.
     *
     * <p>Every multi-style line rebuilt three {@code ArrayList}s, a {@code StringBuilder} and one
     * {@code String} per run, once per frame per line. Neither caller nests into the other — the
     * translation of a run never re-enters this class — so one set of buffers per thread is enough
     * and the two entries below are cleared before they are filled.
     */
    private static final class RunBuffers {
        private final List<String> texts = new ArrayList<String>();
        private final List<Object> styleKeys = new ArrayList<Object>();
        private final List<Style> styles = new ArrayList<Style>();
        private final StringBuilder current = new StringBuilder();

        private void clear() {
            texts.clear();
            styleKeys.clear();
            styles.clear();
            current.setLength(0);
        }
    }

    private static final ThreadLocal<RunBuffers> RUN_BUFFERS = new ThreadLocal<RunBuffers>() {
        @Override
        protected RunBuffers initialValue() {
            return new RunBuffers();
        }
    };

    private RenderedTextBridge() {
    }

    /**
     * Answers without walking anything when translation cannot apply to this line at all.
     *
     * <p>Every render hook calls this for every line of every frame, and the flattening, the style
     * walk and the guard chain behind it are all real work. With translation switched off, or while
     * the local player is typing, the only correct answer is the line itself. The enabled flag is
     * the published settings snapshot, so this is one volatile read and no allocation.
     */
    private static boolean renderTranslationOff() {
        return TranslationRenderContext.isTextInput()
                || !ForgeTranslationRuntime.homeSettings().isEnabled();
    }

    public static String translate(String text) {
        // GuiGraphics.drawCenteredString measures through Font.width and then draws through
        // GuiGraphics.drawString, and both are hooked, so the second and third call receive the line
        // this method just returned. Answering from the frame's own output set keeps those repeats off
        // the guard chain and out of the provider.
        if (TranslationRenderContext.isTranslatedResult(text)) {
            return text;
        }
        if (renderTranslationOff()) {
            return text;
        }
        String translated = translateRaw(text);
        if (text == null || text.equals(translated)) {
            return text;
        }
        String styled = styledText(text, translated);
        // Both forms: the measuring hook is handed what the drawing hook then receives, and the two
        // only match when no translated colour is applied.
        TranslationRenderContext.rememberTranslation(translated);
        if (styled != translated) {
            TranslationRenderContext.rememberTranslation(styled);
        }
        return styled;
    }

    /** Styles one already translated line, reusing the answer when the same line is redrawn. */
    private static String styledText(String original, String translated) {
        TranslationTextColor color = ForgeTranslationRuntime.translatedTextColor();
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
        // Same fast path as the ordered overload: building a component is real work, and the hook
        // runs whether or not translation can answer.
        if (renderTranslationOff()) {
            return text;
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
        if (renderTranslationOff()) {
            return text;
        }
        Flattened flattened = FLATTENED.get();
        StringBuilder original = flattened.reset();
        text.accept((index, style, codePoint) -> {
            if (original.length() == 0) {
                flattened.first = style;
            } else if (!flattened.multiStyle && !style.equals(flattened.first)) {
                flattened.multiStyle = true;
            }
            original.appendCodePoint(codePoint);
            return true;
        });
        if (flattened.multiStyle) {
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
                .setStyle(translatedStyle(flattened.first));
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
        RunBuffers buffers = RUN_BUFFERS.get();
        buffers.clear();
        List<String> texts = buffers.texts;
        List<Object> styleKeys = buffers.styleKeys;
        List<Style> styles = buffers.styles;
        StringBuilder current = buffers.current;
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
        if (renderTranslationOff()) {
            return text;
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
        return translateTooltip(lines, false);
    }

    /** Canonical Screen.getTooltipFromItem hook for inventories and containers. */
    public static List<Component> translateItemTooltip(List<Component> lines) {
        if (ITEM_TOOLTIP_REACHED.compareAndSet(false, true)) {
            DebugLog.global().log("item-tooltip", "producer", "reached");
        }
        List<Component> result = translateTooltip(lines, true);
        if (result != lines) {
            // Marked here, on the producing side, because that is the list the consuming hook then
            // receives. Registering only inside the consumer meant the consumer's own answer was
            // marked instead: the producer's list was never in the set, so every hover translated
            // the same lines a second time.
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
        RunBuffers buffers = RUN_BUFFERS.get();
        buffers.clear();
        List<String> texts = buffers.texts;
        List<Object> styleKeys = buffers.styleKeys;
        List<Style> styles = buffers.styles;
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
