package org.universaltranslator.core;

/**
 * Which corner of the HUD the always-on translation status indicator is painted in.
 *
 * <p>Follows the config-name convention of {@link TranslationDisplayMode}: {@link #fromConfig}
 * accepts the hyphenated form written to the properties file and {@link #configName()} returns it.
 *
 * <p>The two {@code is*} helpers exist so the drawing mixin can compute the origin from local
 * variables alone. That mixin deliberately declares no fields and no helper methods of its own,
 * because a mixin member can collide with a member of its target class.
 *
 * <p>Unlike {@link TranslationDisplayMode#fromConfig}, an unrecognised value falls back to
 * {@link #TOP_LEFT} rather than throwing. The indicator is purely cosmetic, so a hand-edited
 * properties file must never be able to fail configuration loading: that exception would surface
 * from the config's {@code load()} during runtime initialisation.
 */
public enum HudIndicatorCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT;

    /** True when the indicator is anchored to the right edge of the scaled window. */
    public boolean isRight() {
        return this == TOP_RIGHT || this == BOTTOM_RIGHT;
    }

    /** True when the indicator is anchored to the bottom edge of the scaled window. */
    public boolean isBottom() {
        return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
    }

    public static HudIndicatorCorner fromConfig(String value) {
        if (value == null || value.trim().isEmpty()) {
            return TOP_LEFT;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("top-right".equals(normalized) || "top_right".equals(normalized)) {
            return TOP_RIGHT;
        }
        if ("bottom-left".equals(normalized) || "bottom_left".equals(normalized)) {
            return BOTTOM_LEFT;
        }
        if ("bottom-right".equals(normalized) || "bottom_right".equals(normalized)) {
            return BOTTOM_RIGHT;
        }
        // "top-left" and anything unrecognised both land here: never fail on a cosmetic setting.
        return TOP_LEFT;
    }

    public String configName() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
