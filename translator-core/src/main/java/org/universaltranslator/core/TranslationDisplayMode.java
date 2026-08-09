package org.universaltranslator.core;

/** Controls whether the rendered result replaces or accompanies the original server text. */
public enum TranslationDisplayMode {
    TRANSLATED_ONLY,
    ORIGINAL_AND_TRANSLATED;

    public static TranslationDisplayMode fromConfig(String value) {
        if (value == null) {
            return TRANSLATED_ONLY;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("translated-only".equals(normalized) || "translated_only".equals(normalized)) {
            return TRANSLATED_ONLY;
        }
        if ("bilingual".equals(normalized) || "original-and-translated".equals(normalized)
                || "original_and_translated".equals(normalized)) {
            return ORIGINAL_AND_TRANSLATED;
        }
        throw new IllegalArgumentException("Unsupported display mode: " + value);
    }
}
