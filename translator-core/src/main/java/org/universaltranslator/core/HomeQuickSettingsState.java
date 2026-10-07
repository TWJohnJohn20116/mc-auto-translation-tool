package org.universaltranslator.core;

/**
 * Secret-free snapshot of the master switch, vanilla-UI switch, target language, and HUD indicator.
 *
 * <p>This is what the HUD mixins read: the platform config classes are package-private and live in
 * a different package, so the mixins cannot reach them directly and go through this snapshot
 * instead.
 */
public final class HomeQuickSettingsState {
    private final boolean enabled;
    private final boolean translateVanilla;
    private final String targetLanguage;
    private final boolean hudIndicator;
    private final HudIndicatorCorner hudIndicatorCorner;

    public HomeQuickSettingsState(
            boolean enabled,
            boolean translateVanilla,
            String targetLanguage,
            boolean hudIndicator,
            HudIndicatorCorner hudIndicatorCorner) {
        this.enabled = enabled;
        this.translateVanilla = translateVanilla;
        this.targetLanguage = TargetLanguage.canonicalize(targetLanguage);
        this.hudIndicator = hudIndicator;
        // Never null: the HUD mixins call isRight()/isBottom() on this every frame, and the callers
        // that build this snapshot without a loaded config have no corner to pass.
        this.hudIndicatorCorner = hudIndicatorCorner == null
                ? HudIndicatorCorner.TOP_LEFT
                : hudIndicatorCorner;
    }

    /**
     * Convenience constructor for the platforms that have no HUD indicator at all: the indicator
     * only exists from 1.21 onward, so the older runtimes have no values to thread through. Their
     * snapshot still carries the two HUD fields so the type stays uniform, but nothing reads them
     * there. Keeping this overload avoids editing nine runtime factories to pass settings that
     * cannot be reached on those versions.
     */
    public HomeQuickSettingsState(boolean enabled, boolean translateVanilla, String targetLanguage) {
        this(enabled, translateVanilla, targetLanguage, true, HudIndicatorCorner.TOP_LEFT);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isTranslateVanilla() {
        return translateVanilla;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public boolean isHudIndicator() {
        return hudIndicator;
    }

    public HudIndicatorCorner getHudIndicatorCorner() {
        return hudIndicatorCorner;
    }
}
