package org.universaltranslator.core;

/** Converts internal provider status text into localized UI messages. */
public final class TranslationStatusLocalizer {
    private TranslationStatusLocalizer() {
    }

    public static String localize(String status, UiTranslator translator) {
        String value = status == null ? "" : status.trim();
        if (value.isEmpty()) {
            return value;
        }
        if ("等待首次离线翻译".equals(value)) {
            return translator.translate("status.universal_translator.waiting_first_offline");
        }
        if ("离线模型运行中".equals(value)) {
            return translator.translate("status.universal_translator.offline_running");
        }
        if ("正在启动离线模型".equals(value)) {
            return translator.translate("status.universal_translator.offline_starting");
        }
        if ("离线模型已就绪".equals(value)) {
            return translator.translate("status.universal_translator.offline_ready");
        }
        if ("离线引擎下载并校验完成".equals(value)) {
            return translator.translate("status.universal_translator.engine_verified");
        }
        if ("正在安装离线引擎".equals(value)) {
            return translator.translate("status.universal_translator.engine_installing");
        }
        if ("离线模型下载并校验完成".equals(value)) {
            return translator.translate("status.universal_translator.model_verified");
        }
        if ("离线模型已停止".equals(value)) {
            return translator.translate("status.universal_translator.offline_stopped");
        }
        // The engine's own start failures. They are progress messages rather than end states, and
        // before this they fell through to the raw literal, so an English or Japanese interface
        // showed Simplified Chinese in the HUD.
        if ("离线引擎启动失败，正在使用兼容模式重试".equals(value)
                || "离线引擎端口被占用，正在换端口重试".equals(value)) {
            return translator.translate("status.universal_translator.offline_starting");
        }
        if ("离线引擎启动失败，正在自动修复".equals(value)) {
            return translator.translate("status.universal_translator.engine_installing");
        }
        if ("主翻译服务运行中".equals(value)) {
            return translator.translate("status.universal_translator.primary_running");
        }
        if ("主翻译服务失败，已使用 API 回退".equals(value)) {
            return translator.translate("status.universal_translator.fallback_used");
        }
        if ("主翻译服务和 API 回退均失败".equals(value)) {
            return translator.translate("status.universal_translator.fallback_failed");
        }
        if (value.startsWith("翻译失败：")) {
            return translator.translate("status.universal_translator.translation_failed",
                    value.substring("翻译失败：".length()));
        }
        if (value.startsWith("离线翻译失败：")) {
            return translator.translate("status.universal_translator.offline_failed",
                    localizeOfflineReason(value.substring("离线翻译失败：".length()), translator));
        }
        if (value.startsWith("正在下载离线引擎（约 ") && value.endsWith(" MB）")) {
            return translator.translate("status.universal_translator.engine_downloading_size",
                    value.substring("正在下载离线引擎（约 ".length(), value.length() - " MB）".length()));
        }
        if (value.startsWith("正在下载离线模型（国内源优先，约 ") && value.endsWith(" MB）")) {
            return translator.translate("status.universal_translator.model_downloading_size",
                    value.substring("正在下载离线模型（国内源优先，约 ".length(),
                            value.length() - " MB）".length()));
        }
        if (value.startsWith("正在下载离线引擎：")) {
            return translator.translate("status.universal_translator.engine_downloading_progress",
                    normalizeMetrics(value.substring("正在下载离线引擎：".length())));
        }
        if (value.startsWith("正在下载离线模型：")) {
            return translator.translate("status.universal_translator.model_downloading_progress",
                    normalizeMetrics(value.substring("正在下载离线模型：".length())));
        }
        return value;
    }

    public static boolean isFailure(String status) {
        String value = status == null ? "" : status.trim();
        // "失败" covers the translation failures, the "both failed" status and the engine's own start
        // failures, which used to be classified as success: the HUD drew them in the healthy colour
        // and raised no failure notice while the offline engine could not start at all.
        // "端口被占用" is a start failure that reports itself as occupied rather than failed.
        return value.contains("失败") || value.contains("端口被占用");
    }

    private static String normalizeMetrics(String value) {
        return value.replace('（', '(').replace('）', ')').trim();
    }

    /**
     * Localizes the reason the offline engine reported.
     *
     * <p>{@code LlamaCppOfflineProvider} throws its reasons as Chinese literals, so the
     * {@code offline_failed} message used to splice raw Chinese into an English or Japanese UI. The
     * reasons it can produce are mapped here; anything else is passed through untouched, because it may
     * be an operating-system message the player has to read verbatim.
     */
    private static String localizeOfflineReason(String reason, UiTranslator translator) {
        String trimmed = reason == null ? "" : reason.trim();
        if ("离线模型未安装，且自动下载已关闭".equals(trimmed)) {
            return translator.translate("status.universal_translator.offline_model_missing");
        }
        if ("离线引擎未安装，且自动下载已关闭".equals(trimmed)) {
            return translator.translate("status.universal_translator.offline_engine_missing");
        }
        if ("离线模型启动已被取消".equals(trimmed)) {
            return translator.translate("status.universal_translator.offline_cancelled");
        }
        if ("离线模型正在启动中".equals(trimmed)) {
            return translator.translate("status.universal_translator.offline_starting_busy");
        }
        if ("离线模型已停止".equals(trimmed)) {
            return translator.translate("status.universal_translator.offline_stopped");
        }
        // The five known reasons are compared trimmed, so the fallback must be trimmed too: an
        // untrimmed or null reason reached the message as a padding-filled string or as "null".
        return trimmed;
    }
}
