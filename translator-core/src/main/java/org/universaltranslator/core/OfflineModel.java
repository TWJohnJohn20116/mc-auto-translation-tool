package org.universaltranslator.core;

import java.util.Locale;

/** Supported offline models and their user-facing metadata. */
public enum OfflineModel {
    LITE(
            "lite",
            "Lite",
            "qwen2.5-0.5b-instruct-q4-k-m",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491_400_032L),
    QUALITY(
            "quality",
            "Quality",
            "qwen2.5-1.5b-instruct-q4-k-m",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1_117_320_736L);

    private final String configName;
    private final String displayName;
    private final String modelId;
    private final String modelFile;
    private final long expectedBytes;

    OfflineModel(
            String configName,
            String displayName,
            String modelId,
            String modelFile,
            long expectedBytes
    ) {
        this.configName = configName;
        this.displayName = displayName;
        this.modelId = modelId;
        this.modelFile = modelFile;
        this.expectedBytes = expectedBytes;
    }

    public String configName() {
        return configName;
    }

    public String displayName() {
        return displayName;
    }

    public String modelId() {
        return modelId;
    }

    public String modelFile() {
        return modelFile;
    }

    public long expectedBytes() {
        return expectedBytes;
    }

    public OfflineModel next() {
        return this == LITE ? QUALITY : LITE;
    }

    /** Unknown or malformed values fall back to the lower-resource Lite model. */
    public static OfflineModel fromConfig(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (OfflineModel model : values()) {
            if (model.configName.equals(normalized) || model.modelId.equals(normalized)) {
                return model;
            }
        }
        return LITE;
    }
}
