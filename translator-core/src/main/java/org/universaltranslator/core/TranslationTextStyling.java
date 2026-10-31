package org.universaltranslator.core;

/** Formatting helpers shared by modern and legacy render bridges. */
public final class TranslationTextStyling {
    /**
     * Slots of the per-draw memo below.
     *
     * <p>A screen draws a few dozen translated lines and asks for each of them again on the next
     * frame with the same {@code String} instance the session cache handed out, so the answer is
     * unchanged. A single slot was not enough: the lines interleave, so one entry would be replaced
     * before it was ever read again.
     */
    private static final int MEMO_SLOTS = 64;

    private static final Object[] MEMO_TRANSLATED = new Object[MEMO_SLOTS];
    private static final Object[] MEMO_COLOR = new Object[MEMO_SLOTS];
    private static final boolean[] MEMO_SOURCE_HAD_COLOR = new boolean[MEMO_SLOTS];
    private static final String[] MEMO_STYLED = new String[MEMO_SLOTS];

    private TranslationTextStyling() {
    }

    /**
     * Keeps server-provided colors when the source text is already colorized. The configured
     * translation color remains a fallback for ordinary uncolored text.
     */
    public static String applyTranslatedStyle(
            String original,
            String translated,
            TranslationTextColor color
    ) {
        boolean sourceHasColor = hasLegacyColor(original);
        if (translated == null) {
            return null;
        }
        int slot = System.identityHashCode(translated) & (MEMO_SLOTS - 1);
        if (MEMO_TRANSLATED[slot] == translated && MEMO_COLOR[slot] == color
                && MEMO_SOURCE_HAD_COLOR[slot] == sourceHasColor) {
            return MEMO_STYLED[slot];
        }
        String styled = sourceHasColor ? translated : applyLegacyColor(translated, color);
        // The answer is published before the keys that identify it, so a reader that matches the
        // keys never sees the result of the previous occupant of the slot.
        MEMO_STYLED[slot] = styled;
        MEMO_SOURCE_HAD_COLOR[slot] = sourceHasColor;
        MEMO_COLOR[slot] = color;
        MEMO_TRANSLATED[slot] = translated;
        return styled;
    }

    public static String applyLegacyColor(String text, TranslationTextColor color) {
        if (text == null || color == null || !color.changesColor()) {
            return text;
        }
        StringBuilder output = new StringBuilder(text.length() + 8);
        output.append('\u00a7').append(color.legacyCode());
        // A colour code that precedes the first visible character only restates the default that
        // the configured colour replaces. Every later code is content — the bilingual separator
        // writes "§8| §f" itself — so dropping those erased the grey divider.
        boolean emittedText = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\u00a7' && index + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(index + 1));
                if (isColorCode(code) && !emittedText) {
                    index++;
                    continue;
                }
                output.append(value).append(text.charAt(++index));
                if (code == 'r') {
                    output.append('\u00a7').append(color.legacyCode());
                }
                continue;
            }
            emittedText = true;
            output.append(value);
        }
        output.append('\u00a7').append('r');
        return output.toString();
    }

    private static boolean isColorCode(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'x';
    }

    public static String stripLegacyFormatting(String text) {
        if (text == null || text.indexOf('\u00a7') < 0) {
            return text;
        }
        StringBuilder output = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\u00a7' && index + 1 < text.length()) {
                index++;
            } else {
                output.append(value);
            }
        }
        return output.toString();
    }

    public static boolean hasLegacyColor(String text) {
        if (text == null) {
            return false;
        }
        for (int index = 0; index + 1 < text.length(); index++) {
            if (text.charAt(index) != '\u00a7') {
                continue;
            }
            char code = Character.toLowerCase(text.charAt(index + 1));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'x') {
                return true;
            }
            index++;
        }
        return false;
    }
}
