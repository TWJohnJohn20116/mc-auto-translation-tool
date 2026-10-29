package org.universaltranslator.fabric;

import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.universaltranslator.core.TextKind;
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

    public static List<Text> translateTooltip(List<Text> lines) {
        return translateTooltip(lines, false);
    }

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
