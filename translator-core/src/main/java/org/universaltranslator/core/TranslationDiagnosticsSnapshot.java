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

    public List<String> displayLines() {
        List<String> lines = new ArrayList<String>();
        lines.add("自动翻译：" + onOff(enabled));
        lines.add("配置服务：" + providerLabel(configuredProvider));
        lines.add("运行服务：" + activeProviderLabel());
        lines.add("目标语言：" + (targetLanguage.isEmpty() ? "未设置" : targetLanguage));
        if ("offline".equalsIgnoreCase(configuredProvider)) {
            lines.add("离线模型：" + offlineModel.displayName()
                    + "（预计 " + formatBytes(offlineModel.expectedBytes()) + "）");
            lines.add("模型文件：" + modelFileStatus());
            lines.add("模型自动下载：" + onOff(offlineAutoDownload));
        }
        lines.add("磁盘缓存：" + cacheStatus());
        lines.add("运行状态：" + (runtimeStatus.isEmpty()
                ? (enabled ? "等待首次翻译" : "翻译已关闭")
                : runtimeStatus));
        return Collections.unmodifiableList(lines);
    }

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
        return localizedProvider(configuredProvider, translator);
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
        return translator.translate(value
                ? "value.universal_translator.on" : "value.universal_translator.off");
    }

    private String modelFileStatus() {
        if (modelFileBytes < 0L) {
            return "未安装";
        }
        if (modelFileBytes == offlineModel.expectedBytes()) {
            return "已安装并且大小正确";
        }
        return "大小异常（" + formatBytes(modelFileBytes) + " / "
                + formatBytes(offlineModel.expectedBytes()) + "）";
    }

    private String cacheStatus() {
        if (!diskCache) {
            return "关闭（仅内存）";
        }
        return cacheFileBytes < 0L
                ? "开启（尚未建立文件）"
                : "开启（" + formatBytes(cacheFileBytes) + "）";
    }

    private String activeProviderLabel() {
        if (activeProviderId.isEmpty()) {
            return "未启动";
        }
        if (activeProviderId.startsWith("fallback:")) {
            return "离线模型 + API 回退";
        }
        if (activeProviderId.startsWith("offline-llama:")) {
            return "离线模型";
        }
        return providerLabel(configuredProvider);
    }

    private static String providerLabel(String provider) {
        if ("offline".equalsIgnoreCase(provider)) {
            return "离线";
        }
        if ("libretranslate".equalsIgnoreCase(provider)) {
            return "LibreTranslate";
        }
        if ("tencent-hunyuan".equalsIgnoreCase(provider)) {
            return "腾讯混元";
        }
        return provider.isEmpty() ? "未设置" : provider;
    }

    private static String onOff(boolean value) {
        return value ? "开启" : "关闭";
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(LocaleHolder.ROOT, "%.2f GB", bytes / 1_000_000_000.0d);
        }
        if (bytes >= 1_000_000L) {
            return String.format(LocaleHolder.ROOT, "%.1f MB", bytes / 1_000_000.0d);
        }
        if (bytes >= 1_000L) {
            return String.format(LocaleHolder.ROOT, "%.1f KB", bytes / 1_000.0d);
        }
        return bytes + " B";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeStatus(String value) {
        String clean = clean(value).replace('\r', ' ').replace('\n', ' ');
        clean = clean.replaceAll("(?i)https?://\\S+", "[地址已隐藏]");
        clean = clean.replaceAll(
                "(?i)(api[-_ ]?key|secret[-_ ]?(id|key)|token)\\s*[=:]\\s*\\S+",
                "$1=[已隐藏]");
        return clean;
    }

    private static final class LocaleHolder {
        private static final java.util.Locale ROOT = java.util.Locale.ROOT;
    }
}
