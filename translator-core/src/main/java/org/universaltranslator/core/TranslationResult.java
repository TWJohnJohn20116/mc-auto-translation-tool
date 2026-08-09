package org.universaltranslator.core;

import java.util.Objects;

/** Result returned to a platform adapter. Failures deliberately carry the original text. */
public final class TranslationResult {
    private final String originalText;
    private final String translatedText;
    private final boolean translated;
    private final String errorMessage;

    private TranslationResult(String originalText, String translatedText, boolean translated, String errorMessage) {
        this.originalText = Objects.requireNonNull(originalText, "originalText");
        this.translatedText = Objects.requireNonNull(translatedText, "translatedText");
        this.translated = translated;
        this.errorMessage = errorMessage;
    }

    public static TranslationResult success(String originalText, String translatedText) {
        return new TranslationResult(originalText, translatedText, true, null);
    }

    public static TranslationResult unchanged(String text) {
        return new TranslationResult(text, text, false, null);
    }

    public static TranslationResult failure(String text, String errorMessage) {
        return new TranslationResult(text, text, false, errorMessage == null ? "Translation failed" : errorMessage);
    }

    public String getOriginalText() {
        return originalText;
    }

    public String getTranslatedText() {
        return translatedText;
    }

    public boolean isTranslated() {
        return translated;
    }

    public boolean isFailure() {
        return errorMessage != null;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
