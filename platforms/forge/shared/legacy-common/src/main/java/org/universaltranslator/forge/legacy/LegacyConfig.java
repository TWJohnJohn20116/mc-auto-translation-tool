package org.universaltranslator.forge.legacy;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.LocalConfigSecurity;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TranslationBlocklist;
import org.universaltranslator.core.provider.FallbackTranslationProvider;
import org.universaltranslator.core.provider.LlamaCppOfflineProvider;
import org.universaltranslator.core.provider.OnlineProviderConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

final class LegacyConfig {
    final boolean enabled;
    final boolean translateChat;
    final boolean translateOther;
    final boolean translateVanilla;
    final boolean translateOutgoing;
    final boolean translatePlayerNames;
    final boolean animatedUi;
    final String blockedKeywords;
    final String targetLanguage;
    final String outgoingTargetLanguage;
    final TranslationDisplayMode displayMode;
    final boolean translateEnglishOnly;
    final TranslationTextColor translatedTextColor;
    final String provider;
    final String endpoint;
    final String apiKey;
    final String tencentSecretId;
    final String tencentSecretKey;
    final String tencentModel;
    final String llmEndpoint;
    final String llmApiKey;
    final String llmModel;
    final boolean offlineAutoDownload;
    final OfflineModel offlineModel;
    final boolean apiFallback;
    final String apiFallbackProvider;
    final File offlineDirectory;
    final boolean diskCache;
    final File cacheFile;
    final boolean hudIndicator;
    final HudIndicatorCorner hudIndicatorCorner;
    final int hudIndicatorSize;
    final int hudIndicatorMargin;
    final HudIndicatorColor hudIndicatorColor;
    final HudIndicatorContent hudIndicatorContent;
    final HudIndicatorVisibility hudIndicatorVisibility;
    final int hudIndicatorOffsetX;
    final int hudIndicatorOffsetY;
    private final OnlineProviderConfig onlineProviderConfig;
    private final File configFile;

    private LegacyConfig(Properties properties, File configFile, File cacheFile) {
        enabled = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
        translateChat = Boolean.parseBoolean(properties.getProperty("translate-chat", "true"));
        translateOther = Boolean.parseBoolean(properties.getProperty("translate-other", "true"));
        translateVanilla = Boolean.parseBoolean(
                properties.getProperty("translate-vanilla", "true"));
        translateOutgoing = Boolean.parseBoolean(
                properties.getProperty("translate-outgoing", "false"));
        translatePlayerNames = Boolean.parseBoolean(
                properties.getProperty("translate-player-names", "false"));
        animatedUi = Boolean.parseBoolean(properties.getProperty("animated-ui", "true"));
        blockedKeywords = boundedKeywords(properties.getProperty("blocked-keywords", ""));
        targetLanguage = properties.getProperty("target-language", "zh-CN").trim();
        outgoingTargetLanguage = properties.getProperty(
                "outgoing-target-language", "en").trim();
        displayMode = TranslationDisplayMode.fromConfig(
                properties.getProperty("display-mode", "translated-only"));
        translateEnglishOnly = Boolean.parseBoolean(
                properties.getProperty("translate-english-only", "true"));
        translatedTextColor = TranslationTextColor.fromConfig(
                properties.getProperty("translated-text-color", "aqua"));
        provider = properties.getProperty("provider", "offline").trim();
        endpoint = properties.getProperty(
                "libretranslate-endpoint", "http://127.0.0.1:5000/translate").trim();
        apiKey = properties.getProperty("api-key", "").trim();
        tencentSecretId = properties.getProperty("tencent-secret-id", "").trim();
        tencentSecretKey = properties.getProperty("tencent-secret-key", "").trim();
        tencentModel = properties.getProperty(
                "tencent-model", "hunyuan-translation-lite").trim();
        llmEndpoint = properties.getProperty(
                "llm-api-endpoint", "http://127.0.0.1:8080/v1/chat/completions").trim();
        llmApiKey = properties.getProperty("llm-api-key", "").trim();
        llmModel = properties.getProperty("llm-api-model", "local-model").trim();
        offlineAutoDownload = Boolean.parseBoolean(
                properties.getProperty("offline-auto-download", "true"));
        offlineModel = OfflineModel.fromConfig(properties.getProperty("offline-model", "lite"));
        apiFallback = Boolean.parseBoolean(properties.getProperty("api-fallback", "false"));
        apiFallbackProvider = properties.getProperty(
                "api-fallback-provider", "libretranslate").trim();
        diskCache = Boolean.parseBoolean(properties.getProperty("disk-cache", "true"));
        onlineProviderConfig = OnlineProviderConfig.from(properties);
        this.configFile = configFile;
        this.cacheFile = cacheFile;
        this.offlineDirectory = new File(configFile.getParentFile(), "universal-translator-offline");
        this.hudIndicator = Boolean.parseBoolean(properties.getProperty("hud-indicator", "true"));
        this.hudIndicatorCorner = HudIndicatorCorner.fromConfig(
                properties.getProperty("hud-indicator-corner", "top-left"));
        this.hudIndicatorSize = parseBoundedInt(
                properties.getProperty("hud-indicator-size", "6"), 6,
                HudIndicatorSettings.MIN_SIZE, HudIndicatorSettings.MAX_SIZE);
        this.hudIndicatorMargin = parseBoundedInt(
                properties.getProperty("hud-indicator-margin", "4"), 4,
                HudIndicatorSettings.MIN_MARGIN, HudIndicatorSettings.MAX_MARGIN);
        this.hudIndicatorColor = HudIndicatorColor.fromConfig(
                properties.getProperty("hud-indicator-color", "green"));
        this.hudIndicatorContent = HudIndicatorContent.fromConfig(
                properties.getProperty("hud-indicator-content", "dot"));
        this.hudIndicatorVisibility = HudIndicatorVisibility.fromConfig(
                properties.getProperty("hud-indicator-visibility", "always"));
        this.hudIndicatorOffsetX = parseBoundedInt(
                properties.getProperty("hud-indicator-offset-x", "0"),
                0, HudIndicatorSettings.MIN_OFFSET, HudIndicatorSettings.MAX_OFFSET);
        this.hudIndicatorOffsetY = parseBoundedInt(
                properties.getProperty("hud-indicator-offset-y", "0"),
                0, HudIndicatorSettings.MIN_OFFSET, HudIndicatorSettings.MAX_OFFSET);
    }

    HudIndicatorSettings hudIndicatorSettings() {
        return new HudIndicatorSettings(
                hudIndicator, hudIndicatorCorner, hudIndicatorSize, hudIndicatorMargin, hudIndicatorColor,
                hudIndicatorContent, hudIndicatorVisibility,
                hudIndicatorOffsetX, hudIndicatorOffsetY);
    }

    static LegacyConfig load(File configDirectory) throws IOException {
        if (!configDirectory.exists() && !configDirectory.mkdirs()) {
            throw new IOException("Could not create config directory: " + configDirectory);
        }
        File file = new File(configDirectory, "universal-translator.properties");
        if (!file.exists()) {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                defaults().store(writer,
                        "MC Auto Translation Tool - online translation may send selected game, mod, and modpack text to this endpoint");
            }
        }
        Properties stored = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            stored.load(reader);
        }
        Properties properties = defaults();
        properties.putAll(stored);
        boolean legacyMigration = !stored.containsKey("config-version");
        boolean migrated = configVersion(stored) < 6;
        if (legacyMigration) {
            properties.setProperty("display-mode", "translated-only");
            properties.setProperty("translate-english-only", "true");
            properties.setProperty("translated-text-color", "aqua");
        }
        properties.setProperty("config-version", "7");
        LocalConfigSecurity.restrictToOwner(file.toPath());
        LegacyConfig loaded = new LegacyConfig(
                properties, file, new File(configDirectory, "universal-translator-cache.properties"));
        if (migrated) {
            loaded.save();
        }
        return loaded;
    }

    LegacyConfig withSettings(
            boolean enabled,
            boolean translateChat,
            boolean translateOther,
            boolean translateVanilla,
            boolean translateOutgoing,
            boolean translatePlayerNames,
            String blockedKeywords,
            String targetLanguage,
            String outgoingTargetLanguage,
            TranslationDisplayMode displayMode,
            boolean translateEnglishOnly,
            TranslationTextColor translatedTextColor,
            String provider,
            String endpoint,
            String llmEndpoint,
            String llmApiKey,
            String llmModel,
            boolean offlineAutoDownload,
            OfflineModel offlineModel,
            boolean apiFallback,
            boolean diskCache,
            boolean animatedUi,
            HudIndicatorSettings hudIndicator
    ) {
        Properties properties = toProperties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("translate-chat", Boolean.toString(translateChat));
        properties.setProperty("translate-other", Boolean.toString(translateOther));
        properties.setProperty("translate-vanilla", Boolean.toString(translateVanilla));
        properties.setProperty("translate-outgoing", Boolean.toString(translateOutgoing));
        properties.setProperty("translate-player-names", Boolean.toString(translatePlayerNames));
        properties.setProperty("blocked-keywords", boundedKeywords(blockedKeywords));
        properties.setProperty("target-language", targetLanguage.trim());
        properties.setProperty("outgoing-target-language", outgoingTargetLanguage.trim());
        properties.setProperty("display-mode", displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                ? "bilingual" : "translated-only");
        properties.setProperty("translate-english-only", Boolean.toString(translateEnglishOnly));
        properties.setProperty("translated-text-color", translatedTextColor.configName());
        properties.setProperty("provider", provider.trim());
        properties.setProperty("libretranslate-endpoint", endpoint.trim());
        OnlineProviderConfig.applyLlmEditorSettings(
                properties, provider, llmEndpoint, llmApiKey, llmModel);
        properties.setProperty("offline-auto-download", Boolean.toString(offlineAutoDownload));
        properties.setProperty("offline-model",
                (offlineModel == null ? OfflineModel.LITE : offlineModel).configName());
        properties.setProperty("api-fallback", Boolean.toString(apiFallback));
        properties.setProperty("disk-cache", Boolean.toString(diskCache));
        properties.setProperty("animated-ui", Boolean.toString(animatedUi));
        properties.setProperty("hud-indicator", Boolean.toString(hudIndicator.isIndicator()));
        properties.setProperty("hud-indicator-corner", hudIndicator.getCorner().configName());
        properties.setProperty("hud-indicator-size", Integer.toString(hudIndicator.getSize()));
        properties.setProperty("hud-indicator-margin", Integer.toString(hudIndicator.getMargin()));
        properties.setProperty("hud-indicator-color", hudIndicator.getColor().configName());
        properties.setProperty("hud-indicator-content", hudIndicator.getContent().configName());
        properties.setProperty("hud-indicator-visibility", hudIndicator.getVisibility().configName());
        properties.setProperty("hud-indicator-offset-x", Integer.toString(hudIndicator.getOffsetX()));
        properties.setProperty("hud-indicator-offset-y", Integer.toString(hudIndicator.getOffsetY()));
        return new LegacyConfig(properties, configFile, cacheFile);
    }

    LegacyConfig withEnabled(boolean enabled) {
        Properties properties = toProperties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        return new LegacyConfig(properties, configFile, cacheFile);
    }

    LegacyConfig withHomeSettings(
            boolean enabled,
            boolean translateVanilla,
            String targetLanguage
    ) {
        Properties properties = toProperties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("translate-vanilla", Boolean.toString(translateVanilla));
        properties.setProperty("target-language", targetLanguage.trim());
        return new LegacyConfig(properties, configFile, cacheFile);
    }

    void save() throws IOException {
        Path file = configFile.toPath();
        Path temporary = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            Files.deleteIfExists(temporary);
            Files.createFile(temporary);
            LocalConfigSecurity.restrictToOwner(temporary);
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    Files.newOutputStream(temporary,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    StandardCharsets.UTF_8)) {
                toProperties().store(writer,
                        "MC Auto Translation Tool - online translation may send selected game, mod, and modpack text to this endpoint");
            }
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original save failure, if any.
            }
        }
        LocalConfigSecurity.restrictToOwner(file);
    }

    void validateProviderConfiguration() throws Exception {
        TranslationProvider candidate = createProvider();
        if (candidate instanceof AutoCloseable) {
            ((AutoCloseable) candidate).close();
        }
    }

    String editorEndpoint(String selectedProvider) {
        return onlineProviderConfig.llmEditorSettings(selectedProvider).endpoint();
    }

    String editorApiKey(String selectedProvider) {
        return onlineProviderConfig.llmEditorSettings(selectedProvider).apiKey();
    }

    String editorModel(String selectedProvider) {
        return onlineProviderConfig.llmEditorSettings(selectedProvider).model();
    }

    boolean allows(TextKind kind) {
        if (!enabled) {
            return false;
        }
        return kind == TextKind.CHAT || kind == TextKind.SYSTEM_MESSAGE
                ? translateChat
                : translateOther;
    }

    TranslationProvider createProvider() {
        if ("offline".equalsIgnoreCase(provider)) {
            TranslationProvider local = LlamaCppOfflineProvider.forModel(
                    offlineDirectory.toPath(), offlineAutoDownload, offlineModel);
            return apiFallback
                    ? new FallbackTranslationProvider(local, createApiProvider(apiFallbackProvider))
                    : local;
        }
        return createApiProvider(provider);
    }

    private TranslationProvider createApiProvider(String selectedProvider) {
        return onlineProviderConfig.create(selectedProvider);
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        properties.setProperty("config-version", "7");
        properties.setProperty("enabled", "false");
        properties.setProperty("translate-chat", "true");
        properties.setProperty("translate-other", "true");
        properties.setProperty("translate-vanilla", "true");
        properties.setProperty("translate-outgoing", "false");
        properties.setProperty("translate-player-names", "false");
        properties.setProperty("animated-ui", "true");
        properties.setProperty("blocked-keywords", "");
        properties.setProperty("target-language", "zh-CN");
        properties.setProperty("outgoing-target-language", "en");
        properties.setProperty("display-mode", "translated-only");
        properties.setProperty("translate-english-only", "true");
        properties.setProperty("translated-text-color", "aqua");
        properties.setProperty("provider", "offline");
        properties.setProperty("libretranslate-endpoint", "http://127.0.0.1:5000/translate");
        properties.setProperty("api-key", "");
        properties.setProperty("tencent-secret-id", "");
        properties.setProperty("tencent-secret-key", "");
        properties.setProperty("tencent-model", "hunyuan-translation-lite");
        properties.setProperty("llm-api-endpoint", "http://127.0.0.1:8080/v1/chat/completions");
        properties.setProperty("llm-api-key", "");
        properties.setProperty("llm-api-model", "local-model");
        properties.setProperty("offline-auto-download", "true");
        properties.setProperty("offline-model", "lite");
        properties.setProperty("api-fallback", "false");
        properties.setProperty("api-fallback-provider", "libretranslate");
        properties.setProperty("disk-cache", "true");
        OnlineProviderConfig.applyDefaults(properties);
        return properties;
    }

    private Properties toProperties() {
        Properties properties = new Properties();
        onlineProviderConfig.writeTo(properties);
        properties.setProperty("config-version", "7");
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("translate-chat", Boolean.toString(translateChat));
        properties.setProperty("translate-other", Boolean.toString(translateOther));
        properties.setProperty("translate-vanilla", Boolean.toString(translateVanilla));
        properties.setProperty("translate-outgoing", Boolean.toString(translateOutgoing));
        properties.setProperty("translate-player-names", Boolean.toString(translatePlayerNames));
        properties.setProperty("animated-ui", Boolean.toString(animatedUi));
        properties.setProperty("blocked-keywords", blockedKeywords);
        properties.setProperty("target-language", targetLanguage);
        properties.setProperty("outgoing-target-language", outgoingTargetLanguage);
        properties.setProperty("display-mode", displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                ? "bilingual" : "translated-only");
        properties.setProperty("translate-english-only", Boolean.toString(translateEnglishOnly));
        properties.setProperty("translated-text-color", translatedTextColor.configName());
        properties.setProperty("provider", provider);
        properties.setProperty("libretranslate-endpoint", endpoint);
        properties.setProperty("api-key", apiKey);
        properties.setProperty("tencent-secret-id", tencentSecretId);
        properties.setProperty("tencent-secret-key", tencentSecretKey);
        properties.setProperty("tencent-model", tencentModel);
        properties.setProperty("llm-api-endpoint", llmEndpoint);
        properties.setProperty("llm-api-key", llmApiKey);
        properties.setProperty("llm-api-model", llmModel);
        properties.setProperty("offline-auto-download", Boolean.toString(offlineAutoDownload));
        properties.setProperty("offline-model", offlineModel.configName());
        properties.setProperty("api-fallback", Boolean.toString(apiFallback));
        properties.setProperty("api-fallback-provider", apiFallbackProvider);
        properties.setProperty("disk-cache", Boolean.toString(diskCache));
        properties.setProperty("hud-indicator", Boolean.toString(hudIndicator));
        properties.setProperty("hud-indicator-corner", hudIndicatorCorner.configName());
        properties.setProperty("hud-indicator-size", Integer.toString(hudIndicatorSize));
        properties.setProperty("hud-indicator-margin", Integer.toString(hudIndicatorMargin));
        properties.setProperty("hud-indicator-color", hudIndicatorColor.configName());
        properties.setProperty("hud-indicator-content", hudIndicatorContent.configName());
        properties.setProperty("hud-indicator-visibility", hudIndicatorVisibility.configName());
        properties.setProperty("hud-indicator-offset-x", Integer.toString(hudIndicatorOffsetX));
        properties.setProperty("hud-indicator-offset-y", Integer.toString(hudIndicatorOffsetY));
        return properties;
    }

    private static int configVersion(Properties properties) {
        try {
            return Integer.parseInt(properties.getProperty("config-version", "1").trim());
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static int parseBoundedInt(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
    private static String boundedKeywords(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= TranslationBlocklist.MAX_CONFIG_LENGTH
                ? normalized : normalized.substring(0, TranslationBlocklist.MAX_CONFIG_LENGTH);
    }
}

