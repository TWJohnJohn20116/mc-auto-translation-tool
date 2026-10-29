package org.universaltranslator.fabric;

/**
 * Where the title-screen settings button goes.
 *
 * <p>Three generations add the same button and each declares the injection against its own
 * {@code TitleScreen}, but only the widget registration differs between them — {@code addButton},
 * {@code addDrawableChild} or a plain list. The geometry and the label key were copied three times,
 * so every one of them had to be found and changed together, and one of the three had already
 * drifted. The formula lives here instead; the mixins keep the one call their generation needs.
 */
public final class HomeButtonPlacement {
    /** Width of the title-screen button. */
    public static final int WIDTH = 132;
    /** Height of the title-screen button. */
    public static final int HEIGHT = 20;
    /** Distance from the top edge, above the vanilla title logo. */
    public static final int MARGIN_Y = 6;
    /** Smallest distance kept from either side edge. */
    public static final int MARGIN_X = 4;

    private HomeButtonPlacement() {
    }

    /**
     * Left edge of the button on a screen {@code screenWidth} wide: the top-right corner, but never
     * pushed off the left edge by a window narrower than the button plus both margins.
     */
    public static int x(int screenWidth) {
        return Math.max(MARGIN_X, screenWidth - WIDTH - MARGIN_X);
    }
}
