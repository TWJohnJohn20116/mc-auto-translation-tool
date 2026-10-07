package org.universaltranslator.core;

/**
 * Colours the HUD status indicator can be drawn in, for the "on" state.
 *
 * <p>The config names deliberately match {@link TranslationTextColor} so the existing
 * {@code value.universal_translator.color.*} translation keys can be reused instead of
 * duplicating a colour palette. Unlike that enum there is no "original" member: the indicator
 * has no original colour to preserve, it is always drawn by this mod.
 *
 * <p>{@link #fromConfig(String)} falls back to {@link #GREEN} rather than throwing, matching
 * {@link HudIndicatorCorner}: the indicator is cosmetic, so a hand-edited properties file should
 * not be able to fail configuration loading.
 */
public enum HudIndicatorColor {
    GREEN("green", 0xFF55FF55),
    AQUA("aqua", 0xFF55FFFF),
    GOLD("gold", 0xFFFFAA00),
    YELLOW("yellow", 0xFFFFFF55),
    LIGHT_PURPLE("light-purple", 0xFFFF55FF),
    WHITE("white", 0xFFFFFFFF);

    private final String configName;
    private final int argb;

    HudIndicatorColor(String configName, int argb) {
        this.configName = configName;
        this.argb = argb;
    }

    /** Opaque ARGB value, ready to pass to a fill/blit call. */
    public int argb() {
        return argb;
    }

    public String configName() {
        return configName;
    }

    /** Never throws and never returns null: an unrecognised value falls back to {@link #GREEN}. */
    public static HudIndicatorColor fromConfig(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            for (HudIndicatorColor color : values()) {
                if (color.configName.equals(normalized)) {
                    return color;
                }
            }
        }
        return GREEN;
    }
}
