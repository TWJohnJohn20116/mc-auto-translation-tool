package org.universaltranslator.core;

/**
 * Paging arithmetic for the "fetched models" overlay on the engine tab.
 *
 * <p>Every LLM screen copied the same three layout numbers and re-derived the row maths inline, and
 * none of them clamped the current page. {@code modelPage} survives a resize (the overlay stays open),
 * so shrinking the window lowered the page count while the index stayed put: the overlay then rendered
 * an empty page with a page counter that denied it, which reads as "fetching models failed".
 */
public final class ModelListPaging {
    /** Top of the list area, below the overlay's title. */
    public static final int LIST_TOP = 46;
    /** Height of one model row. */
    public static final int ROW_HEIGHT = 12;
    /** Room reserved under the list for the pager and the action buttons. */
    public static final int FOOTER_HEIGHT = 62;

    private ModelListPaging() {
    }

    /** Rows that fit in a screen of the given height; never fewer than three so the list stays usable. */
    public static int rows(int screenHeight) {
        return Math.max(3, (screenHeight - LIST_TOP - FOOTER_HEIGHT) / ROW_HEIGHT);
    }

    /** Number of pages for {@code size} entries; at least one, so an empty list still shows "1/1". */
    public static int pageCount(int size, int rows) {
        int safeRows = Math.max(1, rows);
        return Math.max(1, (Math.max(0, size) + safeRows - 1) / safeRows);
    }

    /** Clamps a page index into range; call this after any resize and before drawing or clicking. */
    public static int clampPage(int page, int size, int rows) {
        return Math.max(0, Math.min(page, pageCount(size, rows) - 1));
    }

    /** Row index under the cursor, relative to the list top. */
    public static int rowAt(double mouseY) {
        return (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
    }

    /** Absolute index into the model list for a page/row pair. */
    public static int index(int page, int rows, int row) {
        return page * Math.max(1, rows) + row;
    }
}
