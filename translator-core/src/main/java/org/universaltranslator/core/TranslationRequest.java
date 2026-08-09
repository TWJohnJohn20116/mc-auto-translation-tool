package org.universaltranslator.core;

import java.util.Objects;

/** Immutable provider request. The text has already had protected tokens replaced. */
public final class TranslationRequest {
    private final String text;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final TextKind kind;

    public TranslationRequest(String text, String sourceLanguage, String targetLanguage, TextKind kind) {
        this.text = Objects.requireNonNull(text, "text");
        this.sourceLanguage = sourceLanguage == null ? "auto" : sourceLanguage;
        this.targetLanguage = Objects.requireNonNull(targetLanguage, "targetLanguage");
        this.kind = kind == null ? TextKind.OTHER : kind;
    }

    public String getText() {
        return text;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public TextKind getKind() {
        return kind;
    }
}
