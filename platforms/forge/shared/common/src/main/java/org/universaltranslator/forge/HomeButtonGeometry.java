package org.universaltranslator.forge;

/**
 * Where the title-screen settings button goes.
 *
 * <p>Every Forge generation adds the same button in its own {@code TitleScreen} mixin and only the
 * widget registration differs between them, but the five numbers — right-aligned, {@code 132} wide,
 * {@code 4} from the edge, {@code 6} from the top, {@code 20} high — were copied into each of them.
 * Changing one always meant finding the others, and one of them had already drifted. The formula
 * lives here once; each mixin keeps the single {@code bounds} call its generation needs.
 *
 * <p>The legacy 1.8.9/1.12.2 tree is a standalone Gradle build that only mounts {@code translator-core}
 * and {@code legacy-common}, so it keeps the same numbers as named constants in
 * {@code LegacyClientEvents}.
 */
public final class HomeButtonGeometry {
    /** Width of the title-screen button. */
    public static final int WIDTH = 132;
    /** Height of the title-screen button. */
    public static final int HEIGHT = 20;
    /** Distance from the top edge, above the vanilla title logo. */
    public static final int MARGIN_Y = 6;
    /** Smallest distance kept from either side edge. */
    public static final int MARGIN_X = 4;

    private HomeButtonGeometry() {
    }

    /**
     * Left edge of the button on a screen {@code screenWidth} wide: the top-right corner, but never
     * pushed off the left edge by a window narrower than the button plus both margins.
     */
    public static int x(int screenWidth) {
        return Math.max(MARGIN_X, screenWidth - WIDTH - MARGIN_X);
    }
}
