package org.universaltranslator.core;

/** Shared responsive geometry for the platform settings screens. */
public final class SettingsScreenLayout {
    public static final int BUTTON_HEIGHT = 20;
    public static final int HEADER_BOTTOM = 34;
    public static final int COMPACT_STATUS_Y = 6;
    /** Narrowest tab that still has room for a two-character label. */
    public static final int MINIMUM_TAB_WIDTH = 20;
    /**
     * Tightest row step the compressed layout will use.
     *
     * <p>A control row is {@link #BUTTON_HEIGHT} high, so below this the rows start to cover each
     * other. It is a floor rather than a target: it only applies when the screen is too short for the
     * full form and the rows have to give way to the action row.
     */
    public static final int MINIMUM_ROW_STEP = 8;

    private SettingsScreenLayout() {
    }

    /** Four tabs, the layout every screen had before the HUD tab was added. */
    public static Geometry calculate(int screenWidth, int screenHeight) {
        return calculate(screenWidth, screenHeight, 4);
    }

    /**
     * @param tabCount number of tab buttons on the screen's tab row; screens without the HUD tab
     *                 keep passing 4 so their geometry is unchanged
     */
    public static Geometry calculate(int screenWidth, int screenHeight, int tabCount) {
        int safeWidth = Math.max(40, screenWidth);
        int availableWidth = Math.max(40, safeWidth - 8);
        int totalWidth = safeWidth >= 200
                ? Math.max(180, Math.min(310, safeWidth - 20))
                : Math.min(310, availableWidth);
        int gap = totalWidth >= 120 ? 8 : 4;
        int buttonWidth = Math.max(16, (totalWidth - gap) / 2);
        int left = Math.max(0, (safeWidth - totalWidth) / 2);

        // At the compact 256px-high GUI scale the former top value was 29, so the
        // animated header divider at y=32..33 crossed the first row of controls.
        // Screens tall enough to fit the complete form now reserve the header first.
        int minimumTop = screenHeight >= 252 ? HEADER_BOTTOM + 2 : 20;
        int top = Math.max(minimumTop,
                Math.min(44, 20 + Math.max(0, screenHeight - 220) / 4));
        int rowStep = screenHeight >= 300 ? 26 : (screenHeight >= 268 ? 22 : 20);
        int targetY = top + rowStep * 7 + 2;
        int endpointY = targetY + (screenHeight >= 300 ? 32 : 28);
        int saveY = screenHeight >= 330
                ? 296 : Math.max(endpointY + 22, screenHeight - 24);

        // Minecraft normally exposes at least 240 logical pixels, but mobile launchers,
        // custom GUI scales and embedded windows can be smaller. Keep the action row on-screen
        // by shifting the form upward instead of leaving Save and Cancel outside mouse reach.
        //
        // The shift itself needs a floor: a 200x160 window moved top from 20 to -56, which put
        // row(0), row(1) and row(2) above the top edge where nothing can be clicked, and traded one
        // unreachable row for another. The form now moves up only as far as the header allows and
        // takes the rest out of the row step, so the whole form stays inside the screen.
        int bottomLimit = Math.max(BUTTON_HEIGHT, screenHeight - 4);
        int overflow = saveY + BUTTON_HEIGHT - bottomLimit;
        if (overflow > 0) {
            int endpointGap = screenHeight >= 300 ? 32 : 28;
            top = Math.max(minimumTop, top - overflow);
            while (rowStep > MINIMUM_ROW_STEP) {
                int candidateTarget = top + rowStep * 7 + 2;
                int candidateEndpoint = candidateTarget + endpointGap;
                int candidateSave = screenHeight >= 330
                        ? 296 : Math.max(candidateEndpoint + 22, screenHeight - 24);
                if (candidateSave + BUTTON_HEIGHT <= screenHeight
                        && candidateEndpoint + BUTTON_HEIGHT <= candidateSave) {
                    break;
                }
                rowStep -= 2;
            }
            targetY = top + rowStep * 7 + 2;
            endpointY = targetY + endpointGap;
            saveY = screenHeight >= 330
                    ? 296 : Math.max(endpointY + 22, screenHeight - 24);
        }

        int tabY = Math.max(22, Math.min(HEADER_BOTTOM + 2, top - 2));
        int safeTabCount = Math.max(1, tabCount);
        int tabGap = 3;
        int tabWidth = Math.max(MINIMUM_TAB_WIDTH, (totalWidth - tabGap * (safeTabCount - 1)) / safeTabCount);
        if (tabWidth * safeTabCount + tabGap * (safeTabCount - 1) > totalWidth) {
            // At a very small GUI scale the five-tab row no longer fits at the legible minimum, and
            // the old code kept the 20px floor anyway, so the last tab was drawn past the right edge
            // and could not be clicked. Tighten the gaps first, then the width, so the row always
            // stays inside the form.
            tabGap = 1;
            tabWidth = Math.max(1, (totalWidth - tabGap * (safeTabCount - 1)) / safeTabCount);
        }
        int contentTop = tabY + BUTTON_HEIGHT + 4;
        int contentRowStep = Math.max(22, Math.min(26, (saveY - contentTop) / 4));

        return new Geometry(left, left + buttonWidth + gap, totalWidth, buttonWidth,
                top, rowStep, targetY, endpointY, saveY,
                tabY, tabWidth, tabGap, contentTop, contentRowStep);
    }

    public static final class Geometry {
        private final int left;
        private final int right;
        private final int totalWidth;
        private final int buttonWidth;
        private final int top;
        private final int rowStep;
        private final int targetY;
        private final int endpointY;
        private final int saveY;
        private final int tabY;
        private final int tabWidth;
        private final int tabGap;
        private final int contentTop;
        private final int contentRowStep;

        private Geometry(int left, int right, int totalWidth, int buttonWidth,
                         int top, int rowStep, int targetY, int endpointY, int saveY,
                         int tabY, int tabWidth, int tabGap, int contentTop, int contentRowStep) {
            this.left = left;
            this.right = right;
            this.totalWidth = totalWidth;
            this.buttonWidth = buttonWidth;
            this.top = top;
            this.rowStep = rowStep;
            this.targetY = targetY;
            this.endpointY = endpointY;
            this.saveY = saveY;
            this.tabY = tabY;
            this.tabWidth = tabWidth;
            this.tabGap = tabGap;
            this.contentTop = contentTop;
            this.contentRowStep = contentRowStep;
        }

        public int left() { return left; }
        public int right() { return right; }
        public int totalWidth() { return totalWidth; }
        public int buttonWidth() { return buttonWidth; }
        public int top() { return top; }
        public int rowStep() { return rowStep; }
        public int targetY() { return targetY; }
        public int endpointY() { return endpointY; }
        public int saveY() { return saveY; }
        public int tabY() { return tabY; }
        public int tabWidth() { return tabWidth; }
        public int tabGap() { return tabGap; }
        public int tabX(int index) { return left + index * (tabWidth + tabGap); }
        public int contentTop() { return contentTop; }
        public int contentRowStep() { return contentRowStep; }
        public int contentRow(int index) { return contentTop + contentRowStep * index; }

        public int row(int index) {
            return top + rowStep * index;
        }
    }
}
