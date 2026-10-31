package org.universaltranslator.core;

import java.util.function.Function;

/** Shared values and compact two-column geometry for in-place settings lists. */
public final class SettingsSelectionList {
    /** Tightest row step the compressed list uses; below this a row stops being a usable target. */
    private static final int MINIMUM_ROW_STEP = 8;
    /** Tightest button the compressed list draws, for the same reason. */
    private static final int MINIMUM_BUTTON_HEIGHT = 8;

    public enum Kind {
        NONE,
        PROVIDER,
        TARGET_LANGUAGE,
        OUTGOING_LANGUAGE
    }

    private SettingsSelectionList() {
    }

    public static String[] values(Kind kind) {
        if (kind == Kind.PROVIDER) {
            return TranslationProviderCatalog.values();
        }
        if (kind == Kind.TARGET_LANGUAGE || kind == Kind.OUTGOING_LANGUAGE) {
            return TargetLanguage.presets();
        }
        return new String[0];
    }

    public static String displayName(Kind kind, String value) {
        return kind == Kind.PROVIDER
                ? TranslationProviderCatalog.displayName(value)
                : TargetLanguage.displayName(value);
    }

    /**
     * Localized label for a selection list row.
     *
     * <p>Provider rows go through {@code localizer} (the screen's {@code tr} adapter) so the list shows
     * the service name in the player's language; target languages keep their self-name, which is a
     * deliberate exception documented on {@link TargetLanguage#displayName(String)}.
     */
    public static String displayName(Kind kind, String value, Function<String, String> localizer) {
        return kind == Kind.PROVIDER
                ? TranslationProviderCatalog.displayName(value, localizer)
                : TargetLanguage.displayName(value);
    }

    public static Layout layout(int screenWidth, int screenHeight, int optionCount) {
        // Mirrors SettingsScreenLayout.calculate: below 200px the 180px floor would push
        // the columns off both edges, so fall back to the available width instead.
        int safeWidth = Math.max(40, screenWidth);
        int availableWidth = Math.max(40, safeWidth - 8);
        int panelWidth = safeWidth >= 200
                ? Math.max(180, Math.min(340, safeWidth - 20))
                : Math.min(340, availableWidth);
        int gap = 4;
        int buttonWidth = (panelWidth - 16 - gap) / 2;
        int left = Math.max(0, (safeWidth - (buttonWidth * 2 + gap)) / 2);
        int rows = Math.max(1, (optionCount + 1) / 2);
        int top = 42;
        int bottomLimit = Math.max(top + rows * 12, screenHeight - 38);
        int rowStep = Math.max(12, Math.min(22, (bottomLimit - top) / rows));
        int buttonHeight = Math.max(11, Math.min(20, rowStep - 2));
        int panelTop = Math.max(8, top - 28);
        // The last row has to end inside the screen, not only inside the panel: the provider list is
        // twenty entries (ten rows), and the old step was derived from a bottom limit that could sit
        // past the bottom edge, so the final option was drawn — and clicked — outside the window.
        // Compressing the step keeps every row reachable instead of trading one row for another.
        while (rows > 1 && rowStep > MINIMUM_ROW_STEP
                && top + (rows - 1) * rowStep + buttonHeight + 4 > screenHeight) {
            rowStep--;
            buttonHeight = Math.max(MINIMUM_BUTTON_HEIGHT, Math.min(buttonHeight, rowStep - 1));
        }
        // Derive the panel bottom from the rows actually drawn so the background always
        // encloses the buttons, even at sub-240 heights where the rows overflow.
        int lastRowBottom = top + (rows - 1) * rowStep + buttonHeight;
        int panelBottom = Math.min(screenHeight,
                Math.max(panelTop + 8, lastRowBottom + 4));
        return new Layout(left, buttonWidth, gap, top, rowStep, buttonHeight,
                panelTop, panelBottom);
    }

    public static final class Layout {
        public final int left;
        public final int buttonWidth;
        public final int gap;
        public final int top;
        public final int rowStep;
        public final int buttonHeight;
        public final int panelTop;
        public final int panelBottom;

        private Layout(int left, int buttonWidth, int gap, int top, int rowStep,
                       int buttonHeight, int panelTop, int panelBottom) {
            this.left = left;
            this.buttonWidth = buttonWidth;
            this.gap = gap;
            this.top = top;
            this.rowStep = rowStep;
            this.buttonHeight = buttonHeight;
            this.panelTop = panelTop;
            this.panelBottom = panelBottom;
        }

        public int x(int index) {
            return left + (index % 2) * (buttonWidth + gap);
        }

        public int y(int index) {
            return top + (index / 2) * rowStep;
        }

        public int panelLeft() {
            return left - 8;
        }

        public int panelRight() {
            return left + buttonWidth * 2 + gap + 8;
        }

        public int optionAt(double mouseX, double mouseY, int optionCount) {
            for (int index = 0; index < optionCount; index++) {
                int x = x(index);
                int y = y(index);
                if (mouseX >= x && mouseX < x + buttonWidth
                        && mouseY >= y && mouseY < y + buttonHeight) {
                    return index;
                }
            }
            return -1;
        }

        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= panelLeft() && mouseX < panelRight()
                    && mouseY >= panelTop && mouseY < panelBottom;
        }
    }
}
