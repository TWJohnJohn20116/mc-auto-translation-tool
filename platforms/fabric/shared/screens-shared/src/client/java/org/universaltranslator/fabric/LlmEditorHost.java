package org.universaltranslator.fabric;

/**
 * The two things the model list editor needs from the screen that opened it. Kept as its own
 * interface so eras where the editor lives in a different source layer can be typed against it
 * without naming a class they cannot see.
 */
interface LlmEditorHost {
    String llmApiKey();

    void applyLlmSettings(String endpoint, String model, String apiKey);
}
