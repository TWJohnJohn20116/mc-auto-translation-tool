package org.universaltranslator.core;

import java.util.Locale;

/**
 * Controls whether the rendered result replaces or accompanies the original server text.
 *
 * <p>{@link #fromConfig(String)} accepts {@code translated-only}, {@code bilingual} and
 * {@code original-and-translated}, ignoring case and treating {@code _} as {@code -}.
 *
 * <p>An unrecognised value falls back to {@link #TRANSLATED_ONLY} rather than throwing. Four configs
 * parse this key inside their own {@code load()}, and an exception there escapes the constructor:
 * the platform bootstrap answers it with {@code shutdown()}, and because the settings screen is
 * built in the same {@code try} block it never opens, so {@code save()} never runs and a
 * hand-edited value stays in the file forever. Falling back keeps the client usable — the contract
 * {@link HudIndicatorCorner} already documents.
 */
public enum TranslationDisplayMode {
    TRANSLATED_ONLY,
    ORIGINAL_AND_TRANSLATED;

    public static TranslationDisplayMode fromConfig(String value) {
        if (value == null || value.trim().isEmpty()) {
            return TRANSLATED_ONLY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("translated-only".equals(normalized) || "translated_only".equals(normalized)) {
            return TRANSLATED_ONLY;
        }
        if ("bilingual".equals(normalized) || "original-and-translated".equals(normalized)
                || "original_and_translated".equals(normalized)) {
            return ORIGINAL_AND_TRANSLATED;
        }
        // The fallback is silent by design, so the debug log is the only place an ignored value
        // can be noticed. This is a no-op while debug logging is disabled.
        DebugLog.global().log("config-fallback", "setting", "display-mode",
                "value", value, "used", "translated-only");
        return TRANSLATED_ONLY;
    }
}
