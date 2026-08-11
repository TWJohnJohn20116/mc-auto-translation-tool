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
