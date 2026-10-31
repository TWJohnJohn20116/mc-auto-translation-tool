package org.universaltranslator.fabric;

import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.StringRenderable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;
import org.universaltranslator.core.TranslationStyleRuns;
import org.universaltranslator.core.WeakIdentitySet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RenderedTextBridge {
    /**
     * Tooltip lists this bridge already produced.
     *
     * <p>Two hooks are mounted on the same line of work: {@code Screen.getTooltipFromItem}
     * produces the list and the renderer consumes it. Both are injected, so without a mark the
     * consumer receives the already translated list and translates it again — a second provider
     * request per hover, and with the English-only filter off the Chinese result is translated a
     * second time and can change between frames.
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
     * and "this version never hooked that entry point" looked identical.
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

    public static Text translate(Text text) {
        if (text == null) {
            return null;
        }
        // Both hooks run for every line of every frame, so answer the cheap question first: while
        // the player is typing, or with the master switch off, the input is the answer and the
        // style walk below is pure cost.
        if (TranslationRenderContext.isTextInput()
                || !FabricTranslationRuntimeBase.renderTranslationEnabled()) {
            return text;
        }
        String original = text.getString();
        // The measuring hook is handed the component the drawing hook already answered.
        if (TranslationRenderContext.isTranslatedResult(original)
                || TranslationRenderContext.isUnchangedResult(original)) {
            return text;
        }
        // Translated before the run walk and handed to it: the walk used to translate every run on
        // its own, so a two-segment chat line sent two requests and each half was translated without
        // the other.
        String translated = translateRaw(original);
        // A component with no children renders exactly its own literal under its own style, so the
        // run walk below would collect a single run and decline to rebuild it every time. Skipping
        // it keeps an ordinary label at one string copy and one cache lookup per frame.
        Text styled = text.getSiblings().isEmpty()
                ? null : translateStyledSiblings(text, original, translated);
        if (styled != null) {
            return styled;
        }
        if (original.equals(translated)) {
            TranslationRenderContext.rememberUnchanged(original);
            return text;
        }
        TranslationRenderContext.rememberTranslation(translated);
        return new LiteralText(translated).setStyle(translatedStyle(text.getStyle()));
    }

    public static StringRenderable translate(StringRenderable text) {
        if (text == null) {
            return null;
        }
        if (text instanceof Text) {
            return translate((Text) text);
        }
        if (TranslationRenderContext.isTextInput()
                || !FabricTranslationRuntimeBase.renderTranslationEnabled()) {
            return text;
        }
        String original = text.getString();
        // Nameplates, chat and the tab list reach both hooks with the same line, so the second call
        // is handed the answer the first one already produced.
        if (TranslationRenderContext.isTranslatedResult(original)
                || TranslationRenderContext.isUnchangedResult(original)) {
            return text;
        }
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            TranslationRenderContext.rememberUnchanged(original);
            return text;
        }
        StringRenderable styled = StringRenderable.styled(translated, translatedStyle(Style.EMPTY));
        TranslationRenderContext.rememberTranslation(translated);
        return styled;
    }

    /** Translates item names and lore before DrawContext creates tooltip components. */
    public static List<Text> translateTooltip(List<Text> lines) {
        if (HANDLED_TOOLTIPS.contains(lines)) {
            // Produced by translateItemTooltip a moment ago; the answer is already in this list.
            return lines;
        }
        return translateTooltip(lines, false);
    }

    /** Canonical Screen.getTooltipFromItem hook for inventories and containers. */
    public static List<Text> translateItemTooltip(List<Text> lines) {
        List<Text> result = translateTooltip(lines, true);
        if (result != lines) {
            // Written at the producing end: the consumer receives this exact list next and must
            // recognise it instead of translating the already translated lines again.
            HANDLED_TOOLTIPS.add(result);
        }
        return result;
    }

    private static List<Text> translateTooltip(List<Text> lines, boolean itemTooltip) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        // A hovered item reaches this once per frame, and with the master switch off every line
        // comes back unchanged.
        if (!FabricTranslationRuntimeBase.renderTranslationEnabled()) {
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
    private static Text translateStyledSiblings(Text text, String visible, String translated) {
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
        // runs indexes into texts, never into styles; keep a misalignment from throwing out of the
        // middle of a draw and losing the frame.
        int firstRun = runs.get(0).sourceIndex();
        if (firstRun < 0 || firstRun >= styles.size()) {
            return null;
        }
        // One translation for the whole line: a run boundary is a style change, not a sentence
        // boundary, and per-run translation sent one request per segment with neither half carrying
        // the context the other provided.
        if (visible.equals(translated)) {
            return null;
        }
        MutableText rebuilt = new LiteralText(translated)
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
     * <p>Children are walked first and each returns the length of its own visible text, so a node
     * never has to flatten a child a second time to work out where its own literal ends. Its own run
     * is then inserted at the offset the subtree started at, which keeps the visible order (a node's
     * text renders before its children). This is the same shape as the 1.16.5 render bridge.</p>
     *
     * @return the visible length of the whole subtree
     */
    private static int collectStyledLeaves(
            Text node,
            List<String> texts,
            List<Object> styleKeys,
            List<Style> styles
    ) {
        int start = texts.size();
        int childLength = 0;
        for (Text child : node.getSiblings()) {
            childLength += collectStyledLeaves(child, texts, styleKeys, styles);
        }
        String full = node.getString();
        // Text a node renders before its children keeps that node's own style.
        if (full.length() > childLength) {
            String own = full.substring(0, full.length() - childLength);
            if (!own.isEmpty()) {
                texts.add(start, own);
                styleKeys.add(start, node.getStyle());
                styles.add(start, node.getStyle());
            }
        }
        return full.length();
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
