package org.universaltranslator.core;

/**
 * What the HUD status indicator shows next to its square.
 *
 * <p>Every value except {@link #PROVIDER} can be answered from data the mixins already have:
 * {@link HomeQuickSettingsState} carries the target language and {@link TranslationActivity}
 * carries the in-flight count. {@link #PROVIDER} needs the configured provider name threaded into
 * the snapshot as well.
 *
 * <p>{@link #fromConfig(String)} falls back to {@link #DOT} rather than throwing, matching
 * {@link HudIndicatorCorner} and {@link HudIndicatorColor}.
 */
public enum HudIndicatorContent {
    /** Just the square, the behaviour before this was configurable. */
    DOT("dot"),
    /** The target language, e.g. "zh-TW". */
    LANGUAGE("language"),
    /** The configured translation provider id. */
    PROVIDER("provider"),
    /** Whether a translation is currently in flight. */
    ACTIVITY("activity");

    private final String configName;

    HudIndicatorContent(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    /** Never throws and never returns null: an unrecognised value falls back to {@link #DOT}. */
    public static HudIndicatorContent fromConfig(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            for (HudIndicatorContent content : values()) {
                if (content.configName.equals(normalized)) {
                    return content;
                }
            }
        }
        return DOT;
    }
}
