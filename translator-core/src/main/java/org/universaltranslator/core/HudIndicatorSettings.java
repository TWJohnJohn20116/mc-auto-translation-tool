package org.universaltranslator.core;

/**
 * Immutable bundle of everything the HUD status indicator needs to draw itself.
 *
 * <p>Exists so {@link HomeQuickSettingsState} does not grow one positional parameter per new
 * cosmetic option: size and margin are both ints, and a swapped argument pair would compile and
 * pass CI while only showing up as a wrong-looking square in game. Each platform config builds
 * this once, which keeps the key-to-field mapping in a single place per config.
 *
 * <p>{@link #defaults()} reproduces the hard-coded indicator that shipped before it was
 * configurable: enabled, top-left, 6x6, 4px margin, green.
 */
public final class HudIndicatorSettings {
    public static final int MIN_SIZE = 2;
    public static final int MAX_SIZE = 16;
    public static final int MIN_MARGIN = 0;
    public static final int MAX_MARGIN = 32;
    public static final int DEFAULT_SIZE = 6;
    public static final int DEFAULT_MARGIN = 4;

    private final boolean indicator;
    private final HudIndicatorCorner corner;
    private final int size;
    private final int margin;
    private final HudIndicatorColor color;

    public HudIndicatorSettings(
            boolean indicator,
            HudIndicatorCorner corner,
            int size,
            int margin,
            HudIndicatorColor color) {
        this.indicator = indicator;
        // Never null: the HUD mixins call isRight()/isBottom() on it every frame.
        this.corner = corner == null ? HudIndicatorCorner.TOP_LEFT : corner;
        this.color = color == null ? HudIndicatorColor.GREEN : color;
        this.size = clamp(size, MIN_SIZE, MAX_SIZE, DEFAULT_SIZE);
        this.margin = clamp(margin, MIN_MARGIN, MAX_MARGIN, DEFAULT_MARGIN);
    }

    public static HudIndicatorSettings defaults() {
        return new HudIndicatorSettings(true, HudIndicatorCorner.TOP_LEFT,
                DEFAULT_SIZE, DEFAULT_MARGIN, HudIndicatorColor.GREEN);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }

    public boolean isIndicator() {
        return indicator;
    }

    public HudIndicatorCorner getCorner() {
        return corner;
    }

    /** Side length of the square, in GUI pixels. Always within {@link #MIN_SIZE}..{@link #MAX_SIZE}. */
    public int getSize() {
        return size;
    }

    /** Distance from the screen edge, in GUI pixels. Always within {@link #MIN_MARGIN}..{@link #MAX_MARGIN}. */
    public int getMargin() {
        return margin;
    }

    /** Colour of the "on" state; the "off" state keeps its fixed warning red. */
    public HudIndicatorColor getColor() {
        return color;
    }
}
