package org.universaltranslator.fabric;

import org.universaltranslator.core.HudIndicatorSettings;

/**
 * Pure geometry of the settings screen's HUD drag preview.
 *
 * <p>Extracted from {@code UniversalTranslatorSettingsScreen} unchanged: the panel rectangle, the
 * letterboxed 320x240 virtual screen inside it, the drag handle and the offset clamp are all
 * arithmetic over the layout and the current indicator settings, and none of them needs a widget,
 * an event or a field of the screen. Keeping them here makes the formulas testable on their own and
 * takes one self-contained responsibility out of a class that also holds forty-three widgets.
 *
 * <p>Every method takes its inputs explicitly and returns a value; nothing here caches or remembers
 * state, so callers may call them as often as they like. The split between the layout snapshot
 * (cached by the caller) and this arithmetic is what keeps a frame allocation-free.
 */
final class HudPreviewGeometry {
    /** The preview panel is a miniature of Minecraft's 320x240 minimum logical screen. */
    static final int SCREEN_WIDTH = 320;
    static final int SCREEN_HEIGHT = 240;
    /** Tallest the preview panel may be. It shrinks on short windows so it never hits Save. */
    static final int MAX_HEIGHT = 48;
    /** Side of the square handle the player drags to move the indicator. */
    static final int HANDLE_SIZE = 8;

    private HudPreviewGeometry() {
    }

    /**
     * Panel height, capped by the space above the Save/Cancel row.
     *
     * <p>{@code contentRow(4)} does not shrink on very short windows because the row step has a
     * floor, so a fixed height would overlap Save. Returning 0 means "there is no room to draw the
     * preview at all", which the callers treat as "skip it".
     */
    static int height(int previewY, int saveY) {
        int available = saveY - 4 - previewY;
        if (available < HANDLE_SIZE) {
            return 0;
        }
        return Math.min(MAX_HEIGHT, available);
    }

    /**
     * One scale for both axes: the smaller of the two fits the whole virtual screen.
     *
     * <p>The panel is wide and short, so the horizontal and vertical factors used to differ by a
     * factor of five, which put the bottom corners in the wrong place and made a pointer delta mean
     * a different number of pixels on each axis.
     */
    static double scale(int previewWidth, int previewHeight) {
        if (previewWidth <= 0 || previewHeight <= 0) {
            return 0.0D;
        }
        return Math.min(previewWidth / (double) SCREEN_WIDTH,
                previewHeight / (double) SCREEN_HEIGHT);
    }

    /** Left edge of the letterboxed screen inside the panel. */
    static int contentX(int previewX, int previewWidth, double scale) {
        int scaled = (int) Math.round(SCREEN_WIDTH * scale);
        return previewX + Math.max(0, (previewWidth - scaled) / 2);
    }

    /** Top edge of the letterboxed screen inside the panel. */
    static int contentY(int previewY, int previewHeight, double scale) {
        int scaled = (int) Math.round(SCREEN_HEIGHT * scale);
        return previewY + Math.max(0, (previewHeight - scaled) / 2);
    }

    /** Handle position for the anchor the indicator hugs, clamped into the panel. */
    static int handleX(int previewX, int previewWidth, int contentX, int anchorX, int offsetX,
            double scale) {
        int x = contentX + (int) Math.round((anchorX + offsetX) * scale);
        return Math.max(previewX, Math.min(previewX + previewWidth - HANDLE_SIZE, x));
    }

    /** Handle position for the anchor the indicator hugs, clamped into the panel. */
    static int handleY(int previewY, int previewHeight, int contentY, int anchorY, int offsetY,
            double scale) {
        int y = contentY + (int) Math.round((anchorY + offsetY) * scale);
        return Math.max(previewY, Math.min(previewY + previewHeight - HANDLE_SIZE, y));
    }

    /** True when the pointer is over the handle square. */
    static boolean insideHandle(double mouseX, double mouseY, int handleX, int handleY) {
        return mouseX >= handleX && mouseX < handleX + HANDLE_SIZE
                && mouseY >= handleY && mouseY < handleY + HANDLE_SIZE;
    }

    /**
     * Bounds an offset so the indicator stays inside the previewed screen.
     *
     * <p>Clamping the offset instead of the drawn position is what removes the dead zone: while
     * only the drawing was clamped the offset kept growing, so the handle stopped following the
     * pointer and the user had to drag back through invisible distance before it moved again.
     */
    static int clampOffset(int value, int anchor, int extent, int indicatorSize) {
        int lower = Math.max(HudIndicatorSettings.MIN_OFFSET, -anchor);
        int upper = Math.min(HudIndicatorSettings.MAX_OFFSET, extent - anchor - indicatorSize);
        if (upper < lower) {
            upper = lower;
        }
        return Math.max(lower, Math.min(upper, value));
    }
}
