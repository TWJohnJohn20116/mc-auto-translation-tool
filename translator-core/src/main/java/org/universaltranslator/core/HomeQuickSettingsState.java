package org.universaltranslator.core;

/** Secret-free state shown by the compact translation controls on Minecraft's title screen. */
public final class HomeQuickSettingsState {
    private final boolean enabled;
    private final boolean translateVanilla;
    private final String targetLanguage;

    public HomeQuickSettingsState(boolean enabled, boolean translateVanilla, String targetLanguage) {
        this.enabled = enabled;
        this.translateVanilla = translateVanilla;
        this.targetLanguage = TargetLanguage.canonicalize(targetLanguage);
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
}
