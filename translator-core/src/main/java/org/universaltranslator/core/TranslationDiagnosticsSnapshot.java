package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A secret-free snapshot suitable for an in-game diagnostics screen. */
public final class TranslationDiagnosticsSnapshot {
    private final boolean enabled;
    private final String configuredProvider;
    private final String activeProviderId;
    private final String targetLanguage;
    private final OfflineModel offlineModel;
    private final boolean offlineAutoDownload;
    private final boolean diskCache;
    private final long modelFileBytes;
    private final long cacheFileBytes;
    private final String runtimeStatus;

    public TranslationDiagnosticsSnapshot(
            boolean enabled,
            String configuredProvider,
            String activeProviderId,
            String targetLanguage,
            OfflineModel offlineModel,
            boolean offlineAutoDownload,
            boolean diskCache,
            long modelFileBytes,
            long cacheFileBytes,
            String runtimeStatus
    ) {
        this.enabled = enabled;
        this.configuredProvider = clean(configuredProvider);
        this.activeProviderId = clean(activeProviderId);
        this.targetLanguage = clean(targetLanguage);
        this.offlineModel = offlineModel == null ? OfflineModel.LITE : offlineModel;
        this.offlineAutoDownload = offlineAutoDownload;
        this.diskCache = diskCache;
        this.modelFileBytes = modelFileBytes;
        this.cacheFileBytes = cacheFileBytes;
        this.runtimeStatus = sanitizeStatus(runtimeStatus);
    }

    /**
     * The snapshot as localizable lines.
     *
     * <p>The only producer path: every diagnostics screen calls this one, and the class no longer
     * carries a second hard-coded copy that a new line could be added to without reaching a screen.
     *
     * @param translator platform language-key resolver
     * @return display lines, never {@code null}
     */
    public List<String> localizedLines(UiTranslator translator) {
        List<String> lines = new ArrayList<String>();
        lines.add(translator.translate("screen.universal_translator.diagnostics.enabled",
                localizedOnOff(enabled, translator)));
        lines.add(translator.translate("screen.universal_translator.diagnostics.configured_provider",
                localizedProvider(configuredProvider, translator)));
        lines.add(translator.translate("screen.universal_translator.diagnostics.active_provider",
                localizedActiveProvider(translator)));
        lines.add(translator.translate("screen.universal_translator.diagnostics.target_language",
                targetLanguage.isEmpty()
                        ? translator.translate("value.universal_translator.not_set") : targetLanguage));
        if ("offline".equalsIgnoreCase(configuredProvider)) {
            lines.add(translator.translate("screen.universal_translator.diagnostics.offline_model",
                    offlineModel.displayName(), formatBytes(offlineModel.expectedBytes())));
            lines.add(translator.translate("screen.universal_translator.diagnostics.model_file",
                    localizedModelFileStatus(translator)));
            lines.add(translator.translate("screen.universal_translator.diagnostics.auto_download",
                    localizedOnOff(offlineAutoDownload, translator)));
        }
        lines.add(translator.translate("screen.universal_translator.diagnostics.disk_cache",
                localizedCacheStatus(translator)));
        String status = runtimeStatus.isEmpty()
                ? translator.translate(enabled
                ? "status.universal_translator.waiting_first_translation"
                : "status.universal_translator.translation_disabled")
                : TranslationStatusLocalizer.localize(runtimeStatus, translator);
        lines.add(translator.translate("screen.universal_translator.diagnostics.runtime_status", status));
        return Collections.unmodifiableList(lines);
    }

    private String localizedModelFileStatus(UiTranslator translator) {
        if (modelFileBytes < 0L) {
            return translator.translate("value.universal_translator.model_not_installed");
        }
        if (modelFileBytes == offlineModel.expectedBytes()) {
            return translator.translate("value.universal_translator.model_valid");
        }
        return translator.translate("value.universal_translator.model_size_invalid",
                formatBytes(modelFileBytes), formatBytes(offlineModel.expectedBytes()));
    }

    private String localizedCacheStatus(UiTranslator translator) {
        if (!diskCache) {
            return translator.translate("value.universal_translator.cache_memory_only");
        }
        return cacheFileBytes < 0L
                ? translator.translate("value.universal_translator.cache_file_pending")
                : translator.translate("value.universal_translator.cache_file_size",
                formatBytes(cacheFileBytes));
    }

    private String localizedActiveProvider(UiTranslator translator) {
        if (activeProviderId.isEmpty()) {
            return translator.translate("value.universal_translator.not_started");
        }
        if (activeProviderId.startsWith("fallback:")) {
            return translator.translate("value.universal_translator.provider_offline_fallback");
        }
        if (activeProviderId.startsWith("offline-llama:")) {
            return translator.translate("value.universal_translator.provider_offline_model");
        }
        // Derived from the id that is actually running: the configured provider is a different
        // field, and showing it here answered "离线" while an API provider was doing the work.
        return localizedProvider(activeProviderCategory(), translator);
    }

    /** The provider category of {@link #activeProviderId}, which is everything before its colon. */
    private String activeProviderCategory() {
        int separator = activeProviderId.indexOf(':');
        return separator < 0 ? activeProviderId : activeProviderId.substring(0, separator);
    }

    private static String localizedProvider(String provider, UiTranslator translator) {
        if ("offline".equalsIgnoreCase(provider)) {
            return translator.translate("value.universal_translator.provider_offline");
        }
        if ("libretranslate".equalsIgnoreCase(provider)) {
            return "LibreTranslate";
        }
        if ("tencent-hunyuan".equalsIgnoreCase(provider)) {
            return translator.translate("value.universal_translator.provider_tencent");
        }
        if ("openai-compatible".equalsIgnoreCase(provider)) {
            return translator.translate("value.universal_translator.provider_llm");
        }
        return provider.isEmpty() ? translator.translate("value.universal_translator.not_set") : provider;
    }

    private static String localizedOnOff(boolean value, UiTranslator translator) {
        return translator.translate(UiTextKeys.onOff(value));
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return fixedPoint(bytes, 1_000_000_000L, 100L) + " GB";
        }
        if (bytes >= 1_000_000L) {
            return fixedPoint(bytes, 1_000_000L, 10L) + " MB";
        }
        if (bytes >= 1_000L) {
            return fixedPoint(bytes, 1_000L, 10L) + " KB";
        }
        return bytes + " B";
    }

    /**
     * {@code bytes / unit} with the last digit rounding half up, using integer arithmetic only.
     *
     * <p>This runs for every size line of a panel that redraws at the frame rate, and
     * {@code String.format} parsed a format string and built a {@code Formatter} per call.
     *
     * @param scale ten for one decimal place, one hundred for two
     */
    private static String fixedPoint(long bytes, long unit, long scale) {
        long scaled = (bytes * scale + unit / 2L) / unit;
        StringBuilder text = new StringBuilder(8);
        text.append(scaled / scale).append('.');
        long fraction = scaled % scale;
        if (scale == 100L && fraction < 10L) {
            text.append('0');
        }
        return text.append(fraction).toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeStatus(String value) {
        // Delegate to the shared exporter logic so the two redaction implementations cannot
        // drift apart again. The placeholders stay localized for the in-game screen.
        return DiagnosticsLogExporter.sanitize(clean(value), "[地址已隐藏]", "[已隐藏]", "[密钥已隐藏]");
    }
}
