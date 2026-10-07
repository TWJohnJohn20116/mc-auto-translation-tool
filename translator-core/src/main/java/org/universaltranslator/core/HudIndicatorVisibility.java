package org.universaltranslator.core;

/**
 * When the HUD status indicator is drawn at all.
 *
 * <p>The point of {@link #WHILE_TRANSLATING} is that the indicator is only interesting while work
 * is happening; users who find it distracting the rest of the time can keep it enabled and still
 * have it out of the way. {@link #WHEN_DISABLED} is the mirror image: show it only when translation
 * is switched off, as a persistent reminder.
 *
 * <p>{@link #fromConfig(String)} falls back to {@link #ALWAYS} rather than throwing, matching
 * {@link HudIndicatorCorner} and {@link HudIndicatorColor}.
 */
public enum HudIndicatorVisibility {
    /** Always drawn while the indicator option is enabled, the behaviour before this was configurable. */
    ALWAYS("always"),
    /** Only drawn while at least one translation is in flight ({@link TranslationActivity}). */
    WHILE_TRANSLATING("while-translating"),
    /**
     * Only drawn while translation itself is switched off
     * ({@code !HomeQuickSettingsState.isEnabled()}), as a reminder.
     *
     * <p>That is the translation master switch, deliberately not the indicator's own option: the
     * indicator option gates every value of this enum equally, so reading it here as well would
     * make this constant unreachable.
     */
    WHEN_DISABLED("when-disabled");

    private final String configName;

    HudIndicatorVisibility(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    /** Never throws and never returns null: an unrecognised value falls back to {@link #ALWAYS}. */
    public static HudIndicatorVisibility fromConfig(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            for (HudIndicatorVisibility visibility : values()) {
                if (visibility.configName.equals(normalized)) {
                    return visibility;
                }
            }
        }
        return ALWAYS;
    }
}
