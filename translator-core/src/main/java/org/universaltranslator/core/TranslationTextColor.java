package org.universaltranslator.core;

import java.util.Locale;

/**
 * Color applied to completed translations so users can distinguish translated text.
 *
 * <p>Follows the config-name convention of {@link TranslationDisplayMode}: {@link #configName()} is
 * the hyphenated form written to the properties file, and {@link #fromConfig(String)} accepts it
 * with either hyphens or underscores, in any case.
 *
 * <p>{@link #fromConfig(String)} falls back to {@link #AQUA} rather than throwing. Four configs
 * parse this key inside their own {@code load()}, and an exception there escapes the constructor:
 * the platform bootstrap answers it with {@code shutdown()}, and because the settings screen is
 * built in the same {@code try} block it never opens, so {@code save()} never runs and a
 * hand-edited value stays in the file forever. The setting is cosmetic, so it must never be able to
 * fail configuration loading — the contract {@link HudIndicatorCorner} already documents.
 */
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
        // The fallback is silent by design, so the debug log is the only place an ignored value
        // can be noticed. This is a no-op while debug logging is disabled.
        DebugLog.global().log("config-fallback", "setting", "translated-text-color",
                "value", value, "used", AQUA.configName());
        return AQUA;
    }
}
