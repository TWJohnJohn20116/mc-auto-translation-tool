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
 * configurable: enabled, top-left, 6x6, 4px margin, green, a plain dot, always visible, and
 * sitting exactly on its corner with no drag offset.
 */
public final class HudIndicatorSettings {
    public static final int MIN_SIZE = 2;
    public static final int MAX_SIZE = 16;
    public static final int MIN_MARGIN = 0;
    public static final int MAX_MARGIN = 32;
    public static final int DEFAULT_SIZE = 6;
    public static final int DEFAULT_MARGIN = 4;

    /**
     * Bounds on how far a drag may push the indicator away from its corner anchor, in GUI pixels.
     *
     * <p>This is a sanity bound on the stored value, not an on-screen guarantee: the config file is
     * plain text a player can edit, so the HUD mixins clamp the final position against the window
     * size as well.
     */
    public static final int MIN_OFFSET = -256;
    public static final int MAX_OFFSET = 256;
    public static final int DEFAULT_OFFSET = 0;

    private final boolean indicator;
    private final HudIndicatorCorner corner;
    private final int size;
    private final int margin;
    private final HudIndicatorColor color;
    private final HudIndicatorContent content;
    private final HudIndicatorVisibility visibility;
    private final int offsetX;
    private final int offsetY;

    /**
     * Full constructor.
     *
     * <p>Only called by the platform configs, which read the keys and pass them positionally.
     * Callers that predate the drag offsets should use the seven-argument overload below, and
     * callers that predate content/visibility should use the five-argument one, instead of
     * repeating the defaults.
     */
    public HudIndicatorSettings(
            boolean indicator,
            HudIndicatorCorner corner,
            int size,
            int margin,
            HudIndicatorColor color,
            HudIndicatorContent content,
            HudIndicatorVisibility visibility,
            int offsetX,
            int offsetY) {
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
        this.offsetX = clamp(offsetX, MIN_OFFSET, MAX_OFFSET, DEFAULT_OFFSET);
        this.offsetY = clamp(offsetY, MIN_OFFSET, MAX_OFFSET, DEFAULT_OFFSET);
    }

    /**
     * Convenience overload for callers that set content and visibility but not the drag offsets.
     *
     * <p>Zero offsets put the indicator back on its corner anchor, which is exactly the behaviour
     * that shipped before dragging existed, so the screens did not have to change at all.
     */
    public HudIndicatorSettings(
            boolean indicator,
            HudIndicatorCorner corner,
            int size,
            int margin,
            HudIndicatorColor color,
            HudIndicatorContent content,
            HudIndicatorVisibility visibility) {
        this(indicator, corner, size, margin, color, content, visibility,
                DEFAULT_OFFSET, DEFAULT_OFFSET);
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

    /**
     * Horizontal drag offset from the corner anchor, in GUI pixels.
     *
     * <p>Always within {@link #MIN_OFFSET}..{@link #MAX_OFFSET}. The anchor still decides which
     * screen edge the indicator hugs, so dragging adds to a corner instead of replacing it and the
     * corner option never becomes dead configuration.
     */
    public int getOffsetX() {
        return offsetX;
    }

    /** Vertical drag offset from the corner anchor. See {@link #getOffsetX()} for the anchor rule. */
    public int getOffsetY() {
        return offsetY;
    }
}
