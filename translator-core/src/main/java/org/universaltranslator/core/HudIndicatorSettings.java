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
 * configurable: enabled, top-left, 6x6, 4px margin, green, a plain dot, always visible.
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
    private final HudIndicatorContent content;
    private final HudIndicatorVisibility visibility;

    /**
     * Full constructor.
     *
     * <p>Only called by the platform configs, which read the keys and pass them positionally.
     * Callers that predate the content/visibility options should use the five-argument overload
     * below instead of repeating the two defaults.
     */
    public HudIndicatorSettings(
            boolean indicator,
            HudIndicatorCorner corner,
            int size,
            int margin,
            HudIndicatorColor color,
            HudIndicatorContent content,
            HudIndicatorVisibility visibility) {
        this.indicator = indicator;
        // Never null: the HUD mixins call isRight()/isBottom() on it every frame.
        this.corner = corner == null ? HudIndicatorCorner.TOP_LEFT : corner;
        this.color = color == null ? HudIndicatorColor.GREEN : color;
        // Also never null: the mixins switch on both every frame, and a null would only surface
        // as a crash inside a render hook.
        this.content = content == null ? HudIndicatorContent.DOT : content;
        this.visibility = visibility == null ? HudIndicatorVisibility.ALWAYS : visibility;
        this.size = clamp(size, MIN_SIZE, MAX_SIZE, DEFAULT_SIZE);
        this.margin = clamp(margin, MIN_MARGIN, MAX_MARGIN, DEFAULT_MARGIN);
    }

    /**
     * Convenience overload for callers that only set the four original options.
     *
     * <p>{@link HudIndicatorContent#DOT} plus {@link HudIndicatorVisibility#ALWAYS} is exactly the
     * behaviour that shipped before content and visibility existed, so keeping this overload means
     * the twelve existing call sites did not have to change at all.
     */
    public HudIndicatorSettings(
            boolean indicator,
            HudIndicatorCorner corner,
            int size,
            int margin,
            HudIndicatorColor color) {
        this(indicator, corner, size, margin, color,
                HudIndicatorContent.DOT, HudIndicatorVisibility.ALWAYS);
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

    /** What the indicator draws inside its square. Never null. */
    public HudIndicatorContent getContent() {
        return content;
    }

    /** When the indicator is drawn at all. Never null. */
    public HudIndicatorVisibility getVisibility() {
        return visibility;
    }
}
