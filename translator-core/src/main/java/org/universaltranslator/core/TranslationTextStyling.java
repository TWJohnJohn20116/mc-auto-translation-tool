package org.universaltranslator.core;

/** Formatting helpers shared by modern and legacy render bridges. */
public final class TranslationTextStyling {
    private TranslationTextStyling() {
    }

    public static String applyLegacyColor(String text, TranslationTextColor color) {
        if (text == null || color == null || !color.changesColor()) {
            return text;
        }
        StringBuilder output = new StringBuilder(text.length() + 8);
        output.append('\u00a7').append(color.legacyCode());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\u00a7' && index + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(index + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    index++;
                    continue;
                }
                output.append(value).append(text.charAt(++index));
                if (code == 'r') {
                    output.append('\u00a7').append(color.legacyCode());
                }
                continue;
            }
            output.append(value);
        }
        output.append('\u00a7').append('r');
        return output.toString();
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
}
