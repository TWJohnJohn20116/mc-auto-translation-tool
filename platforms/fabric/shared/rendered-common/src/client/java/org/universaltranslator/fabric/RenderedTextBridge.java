package org.universaltranslator.fabric;

import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.CharacterVisitor;
import net.minecraft.util.Formatting;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;
import org.universaltranslator.core.TranslationStyleRuns;
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
     * produces the list and {@code DrawContext.drawTooltip} consumes it. Both are injected, so
     * without a mark the second hook receives the already translated list and translates it again
     * — a second provider request per hover, and with the English-only filter off the Chinese
     * result is translated a second time and can change between frames.
     */
    private static final WeakIdentitySet HANDLED_TOOLTIPS = new WeakIdentitySet();
    /**
     * The last line styled and the answer it produced.
     *
     * <p>{@code TranslationTextStyling.applyTranslatedStyle} scans the whole original line for a
     * legacy colour code and, once the player picks a translated colour, rebuilds the translated
     * line character by character into a fresh {@code String}. The same line is drawn once and
     * measured two to four times per frame, so that work used to be repeated for every call even
     * though nothing about the input had changed.
     *
     * <p>One slot of three plain fields, keyed by everything the styling reads — original text,
     * translated text and the configured colour — so a hit is by construction the value a fresh call
     * would return: switching the colour, the target language or the provider all change at least
     * one key. The eight-slot ring this replaces spent sixteen string comparisons on every call and
     * allocated an entry object on every miss, which on a frame that draws a hundred distinct lines
     * was a net loss; the draw hook is followed by the measure hook of the same line, which is
     * exactly what one slot catches. Every field is written before the answer it belongs to, so a
     * racing reader either sees the complete entry or misses and recomputes.
     */
    private static String memoOriginal;
    private static String memoTranslated;
    private static TranslationTextColor memoColor;
    private static String memoStyled;

    /**
     * Scratch lists for the run walk, reused by the render thread they belong to.
     *
     * <p>A multi-style line used to allocate three {@code ArrayList}s per call, and
     * {@code translateStyledSiblings} ran for every component with siblings — a scoreboard side bar
     * or a tab list is one such component per row, every frame. The lists are cleared before each
     * walk and never escape it, so one set per thread is enough.
     *
     * <p>The walk itself is here too: it used to be a capturing lambda over a fresh
     * {@code StringBuilder} and a fresh {@code AtomicReference}, so every multi-style line paid
     * three more allocations per call on the path that exists to avoid allocating.
     */
    private static final class RunScratch implements CharacterVisitor {
        private final List<String> texts = new ArrayList<String>();
        private final List<Object> styleKeys = new ArrayList<Object>();
        private final List<Style> styles = new ArrayList<Style>();
        /** Text of the run being collected; cleared at every style boundary and after each emit. */
        private final StringBuilder run = new StringBuilder();
        /**
         * Style of the run being collected.
         *
         * <p>A one-element array because the visitor needs a mutable answer and this file is
         * compiled for Java 8, where there is no other way to write one into a final field.
         */
        private final Style[] style = new Style[1];

        private void reset() {
            texts.clear();
            styleKeys.clear();
            styles.clear();
            run.setLength(0);
            style[0] = null;
        }

        @Override
        public boolean accept(int index, Style runStyle, int codePoint) {
            if (style[0] == null || !runStyle.equals(style[0])) {
                addRun();
                style[0] = runStyle;
            }
            run.appendCodePoint(codePoint);
            return true;
        }

        /** Emits the run collected so far; the first boundary of a line emits nothing. */
        private void addRun() {
            if (run.length() == 0) {
                return;
            }
            texts.add(run.toString());
            styleKeys.add(style[0]);
            styles.add(style[0]);
            run.setLength(0);
        }
    }

    private static final ThreadLocal<RunScratch> RUNS = new ThreadLocal<RunScratch>() {
        @Override
        protected RunScratch initialValue() {
            return new RunScratch();
        }
    };

    /**
     * One flat copy of an {@code OrderedText}, reused by the render thread it belongs to.
     *
     * <p>Flattening used to allocate a {@code StringBuilder}, an {@code AtomicReference} and a
     * {@code boolean[]} for every drawn line; the builder is the only part that has to grow with the
     * text, so it is kept and reset instead. Nothing nested calls back into this method on the same
     * thread, so one buffer per thread is enough.
     *
     * <p>The visitor is the holder itself, so the {@code accept} call of every drawn line passes one
     * object that already exists instead of a lambda instance capturing the two fields above.
     */
    private static final class Flattened implements CharacterVisitor {
        private final StringBuilder text = new StringBuilder();
        private Style first = Style.EMPTY;
        private boolean multiStyle;

        private StringBuilder reset() {
            text.setLength(0);
            first = Style.EMPTY;
            multiStyle = false;
            return text;
        }

        @Override
        public boolean accept(int index, Style style, int codePoint) {
            if (text.length() == 0) {
                first = style;
            } else if (!multiStyle && !style.equals(first)) {
                multiStyle = true;
            }
            text.appendCodePoint(codePoint);
            return true;
        }
    }

    private static final ThreadLocal<Flattened> FLATTENED = new ThreadLocal<Flattened>() {
        @Override
        protected Flattened initialValue() {
            return new Flattened();
        }
    };

    /**
     * How long one {@link LastResult} answer stays usable.
     *
     * <p>A duration rather than a tick boundary: the measuring hook of a line arrives shortly after
     * its drawing hook and the per-tick reset lives in a class this bridge is compiled without, so
     * fifty milliseconds is far above the repeat this exists to catch and far below the interval at
     * which a line still waiting for an answer has to be asked again to look alive — the same bound
     * the legacy bridge's frame memo uses.
     */
    private static final long FRAME_NANOS = 50L * 1_000_000L;

    /**
     * The answer given to one input a moment ago, so the measuring hook of the same line does not
     * walk it again.
     *
     * <p>Both hooks are {@code @ModifyVariable} on the argument of the drawing call and of
     * {@code getWidth}, and the caller hands the same instance to both, so one slot keyed on that
     * instance answers the second call. The frame's own sets cannot: they hold what the bridge
     * returned, while the measuring hook arrives with the input, so on a line that really was
     * translated the second pass re-ran the guard chain, the provider lookup and the component the
     * line was measured for.
     *
     * <p>Keyed on everything the answer depends on — the instance, the surface kind and the
     * configured colour, the same idea as the styling slot above — so a hit is by construction the
     * value a fresh call would return, and two components with equal text but different styles never
     * collide.
     *
     * <p>An answer is only trusted for the frame that produced it. Switching the target language
     * changes neither the instance nor the colour, and a component whose request the provider had not
     * answered yet has to be asked again — a slot without a time bound answered both of those with
     * the value it took the first time, for as long as the instance stayed alive.
     */
    private static final class LastResult {
        private Object source;
        private TextKind kind;
        private TranslationTextColor color;
        private Object value;
        /** When {@link #value} was stored, in {@link System#nanoTime()}. */
        private long at;

        private Object find(Object candidate, TextKind candidateKind,
                TranslationTextColor candidateColor) {
            if (source != candidate || kind != candidateKind || color != candidateColor) {
                return null;
            }
            if (System.nanoTime() - at > FRAME_NANOS) {
                return null;
            }
            return value;
        }

        private void remember(Object candidate, TextKind candidateKind,
                TranslationTextColor candidateColor, Object answer) {
            source = candidate;
            kind = candidateKind;
            color = candidateColor;
            at = System.nanoTime();
            value = answer;
        }

        /** Drops the slot; called where translation cannot answer, so no answer outlives it. */
        private void forget() {
            source = null;
            kind = null;
            color = null;
            at = 0L;
            value = null;
        }
    }

    private static final LastResult LAST_TEXT = new LastResult();
    private static final LastResult LAST_ORDERED = new LastResult();

    private RenderedTextBridge() {
    }

    /**
     * Records that one injection of this generation really fired, once per session.
     *
     * <p>Every hook is declared {@code require = 0} so a descriptor that drifts between Minecraft
     * versions degrades one surface instead of failing the launch. That choice used to be
     * completely silent: nothing said which surfaces were alive, so "translation stopped working
     * for tooltips" and "this version never hooked tooltips" looked identical. The caller owns the
     * flag, so a repeat costs one atomic read; the debug log is off unless the player enabled it.
     */
    public static void surface(String name, AtomicBoolean once) {
        if (once.compareAndSet(false, true)) {
            DebugLog.global().log("render-surface", "applied", name);
        }
    }

    /** True when this call cannot produce a translation at all; the first question every overload asks. */
    private static boolean bypassRenderTranslation() {
        return TranslationRenderContext.isTextInput()
                || !FabricTranslationRuntimeBase.renderTranslationEnabled();
    }

    public static String translate(String text) {
        // The drawing and measuring hooks both arrive here for every string of every frame, so the
        // two guards the other three overloads already had belong before the lookup — and before
        // anything is done with a null.
        if (text == null || bypassRenderTranslation()) {
            return text;
        }
        if (TranslationRenderContext.isTranslatedResult(text)
                || TranslationRenderContext.isUnchangedResult(text)) {
            // The line this method returned a moment ago: the measuring hook is handed what the
            // drawing hook already drew, so answering from the frame's own output set keeps the
            // repeat off the guard chain and out of the provider.
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
        String cached = memoStyled;
        if (cached != null && memoColor == color
                && (memoTranslated == translated
                || (memoTranslated != null && memoTranslated.equals(translated)))
                && (memoOriginal == original
                || (memoOriginal != null && memoOriginal.equals(original)))) {
            return cached;
        }
        String styled = TranslationTextStyling.applyTranslatedStyle(original, translated, color);
        memoOriginal = original;
        memoTranslated = translated;
        memoColor = color;
        memoStyled = styled;
        return styled;
    }

    public static Text translate(Text text) {
        if (text == null) {
            return null;
        }
        // Same fast path as the OrderedText overload: building a component is real work, and the
        // hook runs whether or not translation can answer.
        if (bypassRenderTranslation()) {
            LAST_TEXT.forget();
            return text;
        }
        // A component with no children renders exactly its own literal under its own style, so the
        // run walk below would collect a single run and decline to rebuild it every time. Skipping
        // it keeps an ordinary label at one string copy and one cache lookup per frame.
        String original = text.getString();
        if (TranslationRenderContext.isUnchangedResult(original)) {
            // Answered a moment ago on the drawing hook with the input itself, so the measuring hook
            // can take the component as it stands instead of walking it again.
            return text;
        }
        TextKind kind = TranslationRenderContext.current();
        TranslationTextColor color = FabricTranslationRuntime.translatedTextColor();
        Text answered = (Text) LAST_TEXT.find(text, kind, color);
        if (answered != null) {
            // The measuring hook of the line the drawing hook answered a moment ago.
            return answered;
        }
        // Translated before the run walk, and handed to it: both paths used to call the same lookup
        // for the same visible text, and the sibling path rebuilt the string a second time for the
        // fallthrough that follows it.
        String translated = translateRaw(original);
        Text styled = text.getSiblings().isEmpty()
                ? null : translateStyledSiblings(text, original, translated);
        if (styled != null) {
            LAST_TEXT.remember(text, kind, color, styled);
            return styled;
        }
        if (original.equals(translated)) {
            // Not stored: the answer here is the input itself, and the frame's own set above already
            // answers the measuring hook, while a stored one kept whichever component was asked last
            // on its original text after the provider had replied.
            TranslationRenderContext.rememberUnchanged(original);
            return text;
        }
        Text replacement = Text.literal(translated).setStyle(translatedStyle(text.getStyle()));
        LAST_TEXT.remember(text, kind, color, replacement);
        return replacement;
    }

    public static OrderedText translate(OrderedText text) {
        if (text == null) {
            return null;
        }
        // Ask the cheap questions first: this hook runs for every line of every frame, and the
        // flattening below is what made it expensive — three allocations and a full codepoint walk
        // per call, before anything was known about the result. With translation switched off, or
        // while the local player is typing, the only correct answer is the line itself.
        if (bypassRenderTranslation()) {
            LAST_ORDERED.forget();
            return text;
        }
        Flattened flattened = FLATTENED.get();
        StringBuilder original = flattened.reset();
        text.accept(flattened);
        String visible = original.toString();
        if (TranslationRenderContext.isUnchangedResult(visible)) {
            // The measuring hook of a line the drawing hook already answered with the input.
            return text;
        }
        TextKind kind = TranslationRenderContext.current();
        TranslationTextColor color = FabricTranslationRuntime.translatedTextColor();
        OrderedText answered = (OrderedText) LAST_ORDERED.find(text, kind, color);
        if (answered != null) {
            // The measuring hook of the line the drawing hook answered a moment ago.
            return answered;
        }
        // One lookup for the whole line, shared by the run rebuild and the fallthrough below: both
        // used to translate the same flattened text, which is twice the work on every line that
        // needed no translation at all.
        String translated = translateRaw(visible);
        if (flattened.multiStyle) {
            // Flattening would keep only the first segment's colour and drop bold, italic and
            // click events from later segments, so rebuild the line run by run instead.
            OrderedText rebuilt = rebuildStyledRuns(text, visible, translated);
            if (rebuilt != null) {
                LAST_ORDERED.remember(text, kind, color, rebuilt);
                return rebuilt;
            }
        }
        if (visible.equals(translated)) {
            // Same reason as the component overload: the frame's unchanged set answers the measuring
            // hook, so nothing is gained by pinning the input in this slot.
            TranslationRenderContext.rememberUnchanged(visible);
            return text;
        }
        MutableText replacement = Text.literal(translated).setStyle(translatedStyle(flattened.first));
        OrderedText ordered = replacement.asOrderedText();
        LAST_ORDERED.remember(text, kind, color, ordered);
        return ordered;
    }

    /**
     * Rebuilds a multi-style ordered line as a translated line, or null when it cannot be rebuilt.
     *
     * <p>The runs are still walked — they are what tells the bridge whether the line carries more
     * than one style — but the line is translated <em>once</em>. A run boundary is a style change,
     * not a sentence boundary: translating each run separately sent two or three requests for one
     * chat line ("[admin]" and "hello world" are two runs of one sentence), each half was translated
     * without the other and often came back unchanged, and every one of them spent a slot of the
     * interactive submission budget. The rebuilt line therefore carries the style of the run its
     * visible text starts with; per-segment styles cannot be mapped onto a translation that no
     * longer aligns segment by segment.
     */
    private static OrderedText rebuildStyledRuns(OrderedText text, String visible, String translated) {
        RunScratch scratch = RUNS.get();
        scratch.reset();
        List<String> texts = scratch.texts;
        List<Object> styleKeys = scratch.styleKeys;
        List<Style> styles = scratch.styles;
        text.accept(scratch);
        scratch.addRun();
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
        if (visible.equals(translated)) {
            return null;
        }
        MutableText rebuilt = Text.literal(translated)
                .setStyle(applyColor(styles.get(runs.get(0).sourceIndex()), color));
        return rebuilt.asOrderedText();
    }

    public static StringVisitable translate(StringVisitable text) {
        if (text == null || bypassRenderTranslation()) {
            return text;
        }
        if (text instanceof Text) {
            return translate((Text) text);
        }
        // Only the two styled paths translate. A plain StringVisitable carries no per-segment
        // styles, and rebuilding it as one styled literal made the measured width disagree with the
        // drawn glyphs — bold and italic change the width, and the separated parts and line breaks
        // were dropped — which shifted every wrap point that used this measurement.
        return text;
    }

    /**
     * Draw-side tooltip hook.
     *
     * <p>No mark is written here: the list this returns is what the game draws, and the mark used to
     * be pushed out of its eight slots by the next hover, so the following frame translated an
     * already translated tooltip a second time. The producer ({@link #translateItemTooltip}) owns the
     * mark, because it is the only place that knows the list it returns is a translation.
     */
    public static List<Text> translateTooltip(List<Text> lines) {
        if (HANDLED_TOOLTIPS.contains(lines)) {
            // Produced by translateItemTooltip a moment ago; the answer is already in this list.
            return lines;
        }
        return translateTooltip(lines, false);
    }

    /** Canonical Screen.getTooltipFromItem hook for inventories and containers. */
    public static List<Text> translateItemTooltip(List<Text> lines) {
        if (ITEM_TOOLTIP_REACHED.compareAndSet(false, true)) {
            // Behind the debug-log switch instead of a bare System.out line: that one could not be
            // turned off and bypassed the redaction every other log path goes through.
            DebugLog.global().log("item-tooltip", "producer", "reached");
        }
        if (HANDLED_TOOLTIPS.contains(lines)) {
            return lines;
        }
        List<Text> result = translateTooltip(lines, true);
        if (result != lines) {
            HANDLED_TOOLTIPS.add(result);
        }
        return result;
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
                        Text.literal(translated).setStyle(translatedStyle(line.getStyle())));
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
    private static Text translateStyledSiblings(Text text, String visible, String translated) {
        RunScratch scratch = RUNS.get();
        scratch.reset();
        List<String> texts = scratch.texts;
        List<Object> styleKeys = scratch.styleKeys;
        List<Style> styles = scratch.styles;
        collectStyledLeaves(text, visible, texts, styleKeys, styles);
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
        // One translation for the whole line, exactly as the ordered path above: a sibling boundary
        // is a style change, so translating each sibling sent one request per segment — none of
        // them carrying enough context to be translated well — and the rebuilt line keeps the style
        // of the segment its visible text starts with. The translation is the caller's, which also
        // needs it for the fallthrough; asking for it here was the duplicate lookup on this path.
        if (visible.equals(translated)) {
            return null;
        }
        MutableText rebuilt = Text.literal(translated)
                .setStyle(applyColor(styles.get(runs.get(0).sourceIndex()), color));
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
            Text node,
            String full,
            List<String> texts,
            List<Object> styleKeys,
            List<Style> styles
    ) {
        List<Text> children = node.getSiblings();
        int childLength = 0;
        String[] childTexts = null;
        if (!children.isEmpty()) {
            // Each child's text is built once and reused by the recursive call: asking every node
            // for its own string and then asking each child again rebuilt the whole subtree twice
            // per level, which is quadratic in the depth of a server-built chat line.
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
