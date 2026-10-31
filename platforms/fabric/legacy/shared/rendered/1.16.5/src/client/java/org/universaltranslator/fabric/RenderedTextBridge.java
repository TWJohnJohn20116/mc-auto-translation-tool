package org.universaltranslator.fabric;

import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.LiteralText;
import net.minecraft.text.StringVisitable;
import net.minecraft.util.Formatting;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;
import org.universaltranslator.core.TranslationStyleRuns;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.WeakIdentitySet;

import java.util.ArrayList;
import java.util.List;

public final class RenderedTextBridge {
    /**
     * Tooltip lists this bridge already produced.
     *
     * <p>Two hooks are mounted on the same line of work: the screen produces the list and the
     * renderer consumes it. Both are injected, so without a mark the consumer receives the already
     * translated list and translates it again — a second provider request per hover, and with the
     * English-only filter off the result is translated a second time.
     */
    private static final WeakIdentitySet HANDLED_TOOLTIPS = new WeakIdentitySet();
    /**
     * Recently styled lines, cycling through a fixed ring.
     *
     * <p>{@code TranslationTextStyling.applyTranslatedStyle} scans the whole original line for a
     * legacy colour code and, once the player picks a translated colour, rebuilds the translated line
     * character by character into a fresh {@code String}. The same line is drawn once and measured two
     * to four times per frame, so that work used to be repeated for every call even though nothing
     * about the input had changed.
     *
     * <p>The key is everything the styling reads — original text, translated text and the configured
     * colour — so a hit is by construction the value a fresh call would return.
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

    public static String translate(String text) {
        String translated = translateRaw(text);
        if (text == null || text.equals(translated)) {
            return text;
        }
        return styledText(text, translated);
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
        // Both hooks run for every line of every frame, so answer the cheap question first: while the
        // player is typing, or with the master switch off, the input is the answer.
        if (TranslationRenderContext.isTextInput()
                || !FabricTranslationRuntimeBase.renderTranslationEnabled()) {
            return text;
        }
        // A component with no children renders exactly its own literal under its own style, so the
        // run walk below would collect a single run and decline to rebuild it every time. Skipping
        // it keeps an ordinary label at one string copy and one cache lookup per frame.
        Text styled = text.getSiblings().isEmpty() ? null : translateStyledSiblings(text);
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

    public static OrderedText translate(OrderedText text) {
        if (text == null) {
            return null;
        }
        // Chat, the tab list and this mod's own text fields all draw through here; while typing (or
        // with translation off) the walk below would only produce the input again.
        if (TranslationRenderContext.isTextInput()
                || !FabricTranslationRuntimeBase.renderTranslationEnabled()) {
            return text;
        }
        // One walk over the code points yields both products: the flattened line (the translation
        // key) and, only if a second style really appears, the per-run slices. This used to walk the
        // line twice - once here and once more in rebuildStyledRuns - for every drawn and every
        // measured line, which is what made chat and the tab list the biggest per-frame allocator.
        StyleRuns runs = new StyleRuns();
        text.accept((index, style, codePoint) -> runs.accept(style, codePoint));
        if (runs.isMultiStyle()) {
            // Flattening would keep only the first segment's colour and drop bold, italic and
            // click events from later segments, so rebuild the line run by run instead.
            OrderedText rebuilt = rebuildStyledRuns(runs);
            if (rebuilt != null) {
                return rebuilt;
            }
        }
        String original = runs.text();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        MutableText replacement = new LiteralText(translated)
                .setStyle(translatedStyle(runs.firstStyle()));
        return replacement.asOrderedText();
    }

    /**
     * Rebuilds a multi-style ordered line as one child per style run, or null when it cannot.
     *
     * <p>Works off the {@link StyleRuns} the single walk already produced: no second pass over the
     * line, and no extra containers when the line carries one style only.
     */
    private static OrderedText rebuildStyledRuns(StyleRuns runs) {
        List<String> texts = runs.runTexts();
        List<Style> styles = runs.runStyles();
        List<Object> styleKeys = new ArrayList<Object>(styles);
        List<TranslationStyleRuns.Run> merged = TranslationStyleRuns.mergeAdjacent(texts, styleKeys);
        if (!TranslationStyleRuns.shouldRebuildRuns(merged)) {
            return null;
        }
        boolean sourceHasColor = false;
        for (Style style : styles) {
            sourceHasColor |= style.getColor() != null;
        }
        TranslationTextColor color = TranslationStyleRuns.resolveTranslatedColor(
                sourceHasColor, FabricTranslationRuntime.translatedTextColor());
        MutableText rebuilt = new LiteralText("");
        for (TranslationStyleRuns.Run run : merged) {
            rebuilt.append(new LiteralText(translateRaw(run.text()))
                    .setStyle(applyColor(styles.get(run.sourceIndex()), color)));
        }
        return rebuilt.asOrderedText();
    }

    /**
     * One ordered line collected in a single pass: the flattened text, the first style, and - only
     * once a second style actually shows up - the run slices.
     *
     * <p>The run list is allocated at the first style change, so a plain chat line costs one
     * {@link StringBuilder} and nothing else. Run boundaries are kept as start offsets into the
     * same buffer, so no per-run string is copied until a rebuild is really needed.
     */
    private static final class StyleRuns {
        private final StringBuilder text = new StringBuilder();
        private Style firstStyle = Style.EMPTY;
        private boolean seenFirstCodePoint;
        private boolean multiStyle;
        private Style openRunStyle;
        private List<Integer> runStarts;
        private List<Style> runStyles;

        private StyleRuns() {
        }

        boolean accept(Style style, int codePoint) {
            if (!seenFirstCodePoint) {
                seenFirstCodePoint = true;
                firstStyle = style;
                openRunStyle = style;
            } else if (!style.equals(openRunStyle)) {
                if (!multiStyle) {
                    multiStyle = true;
                    runStarts = new ArrayList<Integer>();
                    runStyles = new ArrayList<Style>();
                    runStarts.add(Integer.valueOf(0));
                    runStyles.add(openRunStyle);
                }
                runStarts.add(Integer.valueOf(text.length()));
                runStyles.add(style);
                openRunStyle = style;
            }
            text.appendCodePoint(codePoint);
            return true;
        }

        boolean isMultiStyle() {
            return multiStyle;
        }

        String text() {
            return text.toString();
        }

        Style firstStyle() {
            return firstStyle;
        }

        /** Visible text of each run, in draw order. */
        List<String> runTexts() {
            List<String> texts = new ArrayList<String>(runStarts.size());
            for (int index = 0; index < runStarts.size(); index++) {
                int start = runStarts.get(index).intValue();
                int end = index + 1 < runStarts.size()
                        ? runStarts.get(index + 1).intValue()
                        : text.length();
                texts.add(text.substring(start, end));
            }
            return texts;
        }

        /** The style of each run, aligned with {@link #runTexts()}. */
        List<Style> runStyles() {
            return runStyles;
        }
    }

    public static StringVisitable translate(StringVisitable text) {
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
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        return StringVisitable.styled(translated, translatedStyle(Style.EMPTY));
    }

    /** Translates item names and lore before DrawContext creates tooltip components. */
    public static List<Text> translateTooltip(List<Text> lines) {
        return translateTooltip(lines, false);
    }

    /** Canonical Screen.getTooltipFromItem hook for inventories and containers. */
    public static List<Text> translateItemTooltip(List<Text> lines) {
        return translateTooltip(lines, true);
    }

    private static List<Text> translateTooltip(List<Text> lines, boolean itemTooltip) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        if (HANDLED_TOOLTIPS.contains(lines)) {
            // Already produced here: the consuming hook must not translate the result again.
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
        return replacement == null ? lines : replaceAndRemember(lines, replacement);
    }

    /** Marks a freshly built tooltip list so the consuming hook leaves it alone. */
    private static List<Text> replaceAndRemember(List<Text> lines, List<Text> replacement) {
        HANDLED_TOOLTIPS.add(replacement);
        return replacement;
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
     *
     * <p>Invariant: a rebuild may only be returned when the runs cover every visible character, so
     * no node can drop out of the line.
     */
    private static Text translateStyledSiblings(Text text) {
        List<String> texts = new ArrayList<String>();
        List<Object> styleKeys = new ArrayList<Object>();
        List<Style> styles = new ArrayList<Style>();
        collectStyledLeaves(text, texts, styleKeys, styles);
        if (texts.isEmpty()) {
            return null;
        }
        // A non-literal child (a server key message) puts its own text into getString() but never
        // into the run list, so rebuilding a mixed line silently dropped that part of it; falling
        // back to the whole-line path keeps the text and only loses the per-run styles.
        int covered = 0;
        for (String piece : texts) {
            covered += piece.length();
        }
        if (covered != text.getString().length()) {
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
        MutableText rebuilt = new LiteralText("");
        rebuilt.setStyle(text.getStyle());
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(new LiteralText(translateRaw(run.text()))
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
     * <p>Children are walked first and each returns the length of its own visible text, so a node
     * never has to flatten a child a second time to work out where its own literal ends. Its own run
     * is then inserted at the offset the subtree started at, which keeps the visible order (a node's
     * text renders before its children).</p>
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
        // Only a literal node renders a text of its own: for a translatable node {@code getString()}
        // is its formatted value, arguments included, so "full length minus the children" attributed
        // the expanded arguments to the node as well and the same characters reached the run list
        // twice (a guild prefix came out as "<Steve> hiSteve").
        if (node instanceof LiteralText && full.length() > childLength) {
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
