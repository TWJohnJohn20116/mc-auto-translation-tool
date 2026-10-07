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
    private final HudIndicatorSettings hudIndicator;

    public HomeQuickSettingsState(
            boolean enabled,
            boolean translateVanilla,
            String targetLanguage,
            HudIndicatorSettings hudIndicator) {
        this.enabled = enabled;
        this.translateVanilla = translateVanilla;
        this.targetLanguage = TargetLanguage.canonicalize(targetLanguage);
        // Never null: the HUD mixins dereference this every frame.
        this.hudIndicator = hudIndicator == null ? HudIndicatorSettings.defaults() : hudIndicator;
    }

    /**
     * Convenience constructor for the platforms that have no HUD indicator at all: the indicator
     * only exists from 1.14 onward, so the older runtimes have no values to thread through. Their
     * snapshot still carries the settings so the type stays uniform, but nothing reads them there.
     * Keeping this overload avoids editing nine runtime factories to pass settings that cannot be
     * reached on those versions.
     */
    public HomeQuickSettingsState(boolean enabled, boolean translateVanilla, String targetLanguage) {
        this(enabled, translateVanilla, targetLanguage, HudIndicatorSettings.defaults());
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

    public HudIndicatorSettings getHudIndicator() {
        return hudIndicator;
    }
}
