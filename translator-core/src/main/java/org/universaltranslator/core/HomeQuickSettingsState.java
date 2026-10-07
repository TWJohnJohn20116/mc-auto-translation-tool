package org.universaltranslator.core;

/**
 * Secret-free snapshot of the master switch, vanilla-UI switch, target language, HUD indicator,
 * and the active provider id.
 *
 * <p>This is what the HUD mixins read: the platform config classes are package-private and live in
 * a different package, so the mixins cannot reach them directly and go through this snapshot
 * instead.
 *
 * <p>{@code provider} is deliberately only an id such as {@code deepseek}, never an endpoint or an
 * API key: the HUD can show it on screen, and the snapshot must stay safe to hand to a render hook.
 */
public final class HomeQuickSettingsState {
    private final boolean enabled;
    private final boolean translateVanilla;
    private final String targetLanguage;
    private final HudIndicatorSettings hudIndicator;
    private final String provider;

    public HomeQuickSettingsState(
            boolean enabled,
            boolean translateVanilla,
            String targetLanguage,
            HudIndicatorSettings hudIndicator,
            String provider) {
        this.enabled = enabled;
        this.translateVanilla = translateVanilla;
        this.targetLanguage = TargetLanguage.canonicalize(targetLanguage);
        // Never null: the HUD mixins dereference this every frame.
        this.hudIndicator = hudIndicator == null ? HudIndicatorSettings.defaults() : hudIndicator;
        // Never null: the mixins concatenate it into the on-screen label.
        this.provider = provider == null ? "" : provider;
    }

    /**
     * Convenience constructor for the platforms that had an indicator but predate the provider
     * label: the HUD content option is the only thing that reads {@code provider}, and it falls
     * back to a plain dot when it is empty. Keeping this overload means the call sites that do not
     * care about the label did not have to change.
     */
    public HomeQuickSettingsState(
            boolean enabled,
            boolean translateVanilla,
            String targetLanguage,
            HudIndicatorSettings hudIndicator) {
        this(enabled, translateVanilla, targetLanguage, hudIndicator, "");
    }

    /**
     * Convenience constructor for the platforms that have no HUD indicator at all: the indicator
     * only exists from 1.14 onward, so the older runtimes have no values to thread through. Their
     * snapshot still carries the settings so the type stays uniform, but nothing reads them there.
     * Keeping this overload avoids editing nine runtime factories to pass settings that cannot be
     * reached on those versions.
     */
    public HomeQuickSettingsState(boolean enabled, boolean translateVanilla, String targetLanguage) {
        this(enabled, translateVanilla, targetLanguage, HudIndicatorSettings.defaults(), "");
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

    /** Provider id, e.g. {@code deepseek}. Never null; empty when the platform does not report one. */
    public String getProvider() {
        return provider;
    }
}
