package org.universaltranslator.fabric;

import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationStyleRuns;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;
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
     * English-only filter off the Chinese result is translated a second time.
     */
    private static final WeakIdentitySet HANDLED_TOOLTIPS = new WeakIdentitySet();
    /**
     * Recently styled lines, cycling through a fixed ring.
     *
     * <p>{@code applyTranslatedStyle} scans the whole original line for a legacy colour code and
     * rebuilds the translated line character by character into a fresh {@code String}. The same line
     * is drawn once and measured two to four times per frame, so that work was repeated even though
     * nothing about the input had changed. The key is everything the styling reads.
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
        // Early exit for everything drawn under the "this text is not translated" guard (text
        // fields, the HUD indicator's own label, the outgoing preview) and for the master switch
        // being off: translateRaw returns its input unchanged there, so the flatten below could only
        // copy the string. This method is reached by every draw and every width measurement of a
        // component.
        if (TranslationRenderContext.isTextInput()
                || !FabricTranslationRuntimeBase.renderTranslationEnabled()) {
            return text;
        }
        // A component whose visible text does not all render with the root's own style has to be
        // rebuilt run by run: flattening it below keeps the root style alone, which is how a team
        // prefix and the name after it came out in one colour. Asked before getString() so an
        // ordinary label still costs one string copy.
        Text styled = text.getSiblings().isEmpty() ? null : translateStyledSiblings(text);
        if (styled != null) {
            return styled;
        }
        String original = text.getString();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        // The translated-text colour travels as a § code inside the string (that is what
        // TranslationTextStyling produces); this generation has no Style.withColor to build it with.
        // The line's own style object is then applied on top, so rarity colours and bold/italic runs
        // survive instead of every translated component being flattened to plain white.
        return new LiteralText(TranslationTextStyling.applyTranslatedStyle(
                original, translated, FabricTranslationRuntime.translatedTextColor()))
                .setStyle(text.getStyle());
    }

    /**
     * Rebuilds the translation as one child per style the source's visible text renders with, so
     * every server-provided style survives; flattening the component into a single literal keeps
     * only the style of the node it was flattened from. Returns {@code null} when the visible text
     * already renders with the root's own style, so the caller keeps the ordinary path.
     */
    private static Text translateStyledSiblings(Text text) {
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
        MutableText rebuilt = new LiteralText("");
        rebuilt.setStyle(text.getStyle());
        for (TranslationStyleRuns.Run run : runs) {
            String translatedRun = translateRaw(run.text());
            Style runStyle = styles.get(run.sourceIndex());
            if (color == TranslationTextColor.ORIGINAL || translatedRun.equals(run.text())) {
                rebuilt.append(new LiteralText(translatedRun).setStyle(runStyle));
                continue;
            }
            // This generation has no Style.withColor, so the configured colour travels as a legacy
            // code inside the run — and only when the source carries no colour of its own, which is
            // the one case resolveTranslatedColor does not answer ORIGINAL for.
            rebuilt.append(new LiteralText(TranslationTextStyling.applyTranslatedStyle(
                    run.text(), translatedRun, color)).setStyle(runStyle));
        }
        return rebuilt;
    }

    /**
     * Collects the visible text of {@code text} together with the style of the node that carries it,
     * descending into nested children: team prefixes and suffixes arrive as nested trees, so reading
     * only the direct children left the caller with the root style alone.
     *
     * <p>Children are walked first and each returns the length of its own visible text, so a node
     * never has to flatten a child a second time to work out where its own literal ends.
     *
     * @return the visible length of the whole subtree
     */
    private static int collectStyledLeaves(Text node, List<String> texts, List<Object> styleKeys,
            List<Style> styles) {
        int start = texts.size();
        int childLength = 0;
        for (Text child : node.getSiblings()) {
            childLength += collectStyledLeaves(child, texts, styleKeys, styles);
        }
        String full = node.getString();
        // Only a literal node renders a text of its own: for a translatable node getString() is its
        // formatted value, arguments included, so "full length minus the children" attributed the
        // expanded arguments to the node as well and the same characters reached the run list twice.
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

    public static List<Text> translateTooltip(List<Text> lines) {
        return translateTooltip(lines, false);
    }    public static List<Text> translateItemTooltip(List<Text> lines) {
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
                        new LiteralText(TranslationTextStyling.applyTranslatedStyle(
                                original, translated,
                                FabricTranslationRuntime.translatedTextColor()))
                                .setStyle(line.getStyle()));
            }
        }
        if (replacement == null) {
            return lines;
        }
        // Marks the list this bridge produced so the consuming hook leaves it alone.
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
}
