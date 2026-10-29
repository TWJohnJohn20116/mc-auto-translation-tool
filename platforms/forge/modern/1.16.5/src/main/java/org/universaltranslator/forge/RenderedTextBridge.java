package org.universaltranslator.forge;

import net.minecraft.util.IReorderingProcessor;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.ITextProperties;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;
import org.universaltranslator.core.TranslationStyleRuns;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class RenderedTextBridge {
    // Same-line memo in front of the ordered-line rebuild (P2-076). The chat overlay and the HUD
    // redraw identical IReorderingProcessor instances every frame, and the rebuild below costs a
    // full accept() walk plus one StringBuilder, one AtomicReference and three ArrayLists per line.
    // The memo is valid for exactly one client tick, which keeps a freshly finished translation
    // from being deferred by more than 50 ms.
    private static volatile IReorderingProcessor orderMemoInput;
    private static volatile IReorderingProcessor orderMemoOutput;
    private static volatile int orderMemoEpoch;

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

    public static String translate(String text) {
        // Ask the cheap questions first: this hook runs for every line of every frame, and the
        // styling below is what made it expensive.
        if (TranslationRenderContext.isTextInput()
                || !ForgeTranslationRuntime.renderTranslationEnabled()) {
            return text;
        }
        // GuiGraphics.drawCenteredString measures through Font.width and then draws through
        // GuiGraphics.drawString, and both are hooked, so the second and third call receive the
        // line this method just returned.
        if (TranslationRenderContext.isTranslatedResult(text)) {
            return text;
        }
        String translated = translateRaw(text);
        if (text == null || text.equals(translated)) {
            return text;
        }
        TranslationRenderContext.rememberTranslation(translated);
        return styledText(text, translated);
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

    public static ITextComponent translate(ITextComponent text) {
        if (text == null) {
            return null;
        }
        if (TranslationRenderContext.isTextInput()
                || !ForgeTranslationRuntime.renderTranslationEnabled()) {
            return text;
        }
        // A component with no children renders exactly its own literal under its own style, so the
        // run walk below would collect a single run and decline to rebuild it every time. Skipping
        // it keeps an ordinary label at one string copy and one cache lookup per frame.
        ITextComponent styled = text.getSiblings().isEmpty() ? null : translateStyledSiblings(text);
        if (styled != null) {
            return styled;
        }
        String original = text.getString();
        String translated = translateRaw(original);
        if (original.equals(translated)) {
            return text;
        }
        return new StringTextComponent(translated).setStyle(translatedStyle(text.getStyle()));
    }

    public static IReorderingProcessor translate(IReorderingProcessor text) {
        if (text == null) {
            return null;
        }
        // The flattening below costs three allocations and a full codepoint walk per call, before
        // anything is known about the result; with translation off the answer is the line itself.
        if (TranslationRenderContext.isTextInput()
                || !ForgeTranslationRuntime.renderTranslationEnabled()) {
            return text;
        }
        int epoch = ForgeTranslationRuntime.currentRenderEpoch();
        IReorderingProcessor memoised = orderMemoOutput;
        if (memoised != null && orderMemoInput == text && orderMemoEpoch == epoch) {
            return memoised;
        }
        IReorderingProcessor result = translateOrdered(text);
        orderMemoInput = text;
        orderMemoOutput = result;
        orderMemoEpoch = epoch;
        return result;
    }

    private static IReorderingProcessor translateOrdered(IReorderingProcessor text) {
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
            IReorderingProcessor rebuilt = rebuildStyledRuns(text);
            if (rebuilt != null) {
                return rebuilt;
            }
        }
        String translated = translateRaw(original.toString());
        if (original.toString().equals(translated)) {
            return text;
        }
        IFormattableTextComponent replacement = new StringTextComponent(translated)
                .setStyle(translatedStyle(firstStyle.get()));
        return replacement.getVisualOrderText();
    }

    /** Rebuilds a multi-style ordered line as one child per style run, or null when it cannot. */
    private static IReorderingProcessor rebuildStyledRuns(IReorderingProcessor text) {
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
        IFormattableTextComponent rebuilt = new StringTextComponent("");
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(new StringTextComponent(translateRaw(run.text()))
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

    public static ITextProperties translate(ITextProperties text) {
        if (text == null) {
            return null;
        }
        if (TranslationRenderContext.isTextInput()
                || !ForgeTranslationRuntime.renderTranslationEnabled()) {
            return text;
        }
        if (text instanceof ITextComponent) {
            return translate((ITextComponent) text);
        }
        String original = text.getString();
        String translated = translateRaw(original);
        return original.equals(translated)
                ? text : new StringTextComponent(translated).setStyle(translatedStyle(Style.EMPTY));
    }

    public static List<ITextComponent> translateItemTooltip(List<ITextComponent> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<String> originals = new ArrayList<String>(lines.size());
        for (ITextComponent line : lines) {
            originals.add(line == null ? "" : line.getString());
        }
        List<String> translated = ForgeTranslationRuntime.translateLinesForRender(
                originals, TextKind.TOOLTIP);
        List<ITextComponent> result = null;
        for (int index = 0; index < lines.size(); index++) {
            ITextComponent line = lines.get(index);
            if (line != null && !originals.get(index).equals(translated.get(index))) {
                if (result == null) {
                    result = new ArrayList<ITextComponent>(lines);
                }
                result.set(index, new StringTextComponent(translated.get(index))
                        .setStyle(translatedStyle(line.getStyle())));
            }
        }
        return result == null ? lines : result;
    }

    private static String translateRaw(String text) {
        if (TranslationRenderContext.isTextInput()) {
            return text;
        }
        return ForgeTranslationRuntime.translateForRender(text, TranslationRenderContext.current());
    }

    /**
     * Rebuilds the translation as one child per style the source's visible text renders with, so
     * every server-provided style survives; flattening the component into a single literal keeps
     * only the style of the node it was flattened from. Returns {@code null} when the visible text
     * already renders with the root's own style, so the caller keeps the ordinary path.
     */
    private static ITextComponent translateStyledSiblings(ITextComponent text) {
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
        IFormattableTextComponent rebuilt = new StringTextComponent("");
        rebuilt.setStyle(text.getStyle());
        for (TranslationStyleRuns.Run run : runs) {
            rebuilt.append(new StringTextComponent(translateRaw(run.text()))
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
            ITextComponent node,
            List<String> texts,
            List<Object> styleKeys,
            List<Style> styles
    ) {
        List<ITextComponent> children = node.getSiblings();
        String full = node.getString();
        int childLength = 0;
        for (ITextComponent child : children) {
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
        for (ITextComponent child : children) {
            collectStyledLeaves(child, texts, styleKeys, styles);
        }
    }

    private static Style applyColor(Style original, TranslationTextColor color) {
        if (color == null) {
            return original;
        }
        switch (color) {
            case GREEN: return original.withColor(TextFormatting.GREEN);
            case GOLD: return original.withColor(TextFormatting.GOLD);
            case LIGHT_PURPLE: return original.withColor(TextFormatting.LIGHT_PURPLE);
            case YELLOW: return original.withColor(TextFormatting.YELLOW);
            case WHITE: return original.withColor(TextFormatting.WHITE);
            case AQUA: return original.withColor(TextFormatting.AQUA);
            default: return original;
        }
    }

    private static Style translatedStyle(Style original) {
        return applyColor(original, TranslationStyleRuns.resolveTranslatedColor(
                original.getColor() != null,
                ForgeTranslationRuntime.translatedTextColor()));
    }
}
