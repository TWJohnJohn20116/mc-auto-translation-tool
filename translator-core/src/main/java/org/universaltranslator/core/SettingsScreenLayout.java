package org.universaltranslator.core;

/** Shared responsive geometry for the platform settings screens. */
public final class SettingsScreenLayout {
    public static final int BUTTON_HEIGHT = 20;
    public static final int HEADER_BOTTOM = 34;
    public static final int COMPACT_STATUS_Y = 6;

    private SettingsScreenLayout() {
    }

    public static Geometry calculate(int screenWidth, int screenHeight) {
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
        int bottomLimit = Math.max(BUTTON_HEIGHT, screenHeight - 4);
        int overflow = saveY + BUTTON_HEIGHT - bottomLimit;
        if (overflow > 0) {
            top -= overflow;
            targetY = top + rowStep * 7 + 2;
            endpointY = targetY + (screenHeight >= 300 ? 32 : 28);
            saveY = screenHeight >= 330
                    ? 296 : Math.max(endpointY + 22, screenHeight - 24);
        }

        int tabY = Math.max(22, Math.min(HEADER_BOTTOM + 2, top - 2));
        int tabCount = 4;
        int tabGap = 3;
        int tabWidth = Math.max(20, (totalWidth - tabGap * (tabCount - 1)) / tabCount);
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
