package org.universaltranslator.core;

import java.util.Locale;

/**
 * How much instruction the provider is given for one translation.
 *
 * <p>Only the system prompt changes with this setting. The model, the temperature, the completion
 * budget, the batching protocol and the streaming behaviour are deliberately untouched: a "faster"
 * mode that also sampled differently would be a different feature, and it would make a comparison
 * between two modes meaningless because two things would have changed at once.
 *
 * <p>{@link #STANDARD} is the historical prompt, character for character, and is the default, so a
 * configuration that never mentions the setting behaves exactly as it did before this existed.
 */
public enum TranslationQuality {
    /** Fewest instructions, lowest latency; the shortest prompt that still works. */
    FAST("fast"),
    /** The historical prompt; the default. */
    STANDARD("standard"),
    /** Adds strictness: tone, terminology consistency, no added or dropped meaning, line alignment. */
    HIGH("high");

    /** The value used when the configuration file has no usable {@code translation-quality}. */
    public static final TranslationQuality DEFAULT = STANDARD;

    private final String configName;

    TranslationQuality(String configName) {
        this.configName = configName;
    }

    /** The value stored in {@code universal-translator.properties}. */
    public String configName() {
        return configName;
    }

    /** The next mode in the cycle the settings screen walks through. */
    public TranslationQuality next() {
        TranslationQuality[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /**
     * Parses a configured value, falling back to {@link #DEFAULT} for anything unrecognized.
     *
     * <p>An unknown value never fails the load: a configuration written by a newer build, or a
     * typo, must not stop the mod from starting. The fallback is the historical behaviour, which is
     * the safest thing to fall back to.
     *
     * @param value raw configured value, possibly {@code null}
     * @return the matching mode, or {@link #DEFAULT}
     */
    public static TranslationQuality fromConfig(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TranslationQuality candidate : values()) {
            if (candidate.configName.equals(normalized)) {
                return candidate;
            }
        }
        return DEFAULT;
    }

    /** English label used by logs and by the read-only settings panel. */
    public String displayName() {
        switch (this) {
            case FAST: return "Fast";
            case HIGH: return "High";
            case STANDARD:
            default: return "Standard";
        }
    }
}
