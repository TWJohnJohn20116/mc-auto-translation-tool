package org.universaltranslator.core;

import java.util.Locale;

/** Color applied to completed translations so users can distinguish translated text. */
public enum TranslationTextColor {
    ORIGINAL("original", '\0'),
    AQUA("aqua", 'b'),
    GREEN("green", 'a'),
    GOLD("gold", '6'),
    LIGHT_PURPLE("light-purple", 'd'),
    YELLOW("yellow", 'e'),
    WHITE("white", 'f');

    private final String configName;
    private final char legacyCode;

    TranslationTextColor(String configName, char legacyCode) {
        this.configName = configName;
        this.legacyCode = legacyCode;
    }

    public String configName() {
        return configName;
    }

    public boolean changesColor() {
        return legacyCode != '\0';
    }

    public char legacyCode() {
        return legacyCode;
    }

    public TranslationTextColor next() {
        TranslationTextColor[] colors = values();
        return colors[(ordinal() + 1) % colors.length];
    }

    public static TranslationTextColor fromConfig(String value) {
        if (value == null || value.trim().isEmpty()) {
            return AQUA;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (TranslationTextColor color : values()) {
            if (color.configName.equals(normalized)) {
                return color;
            }
        }
        throw new IllegalArgumentException("Unsupported translated text color: " + value);
    }
}
