package org.universaltranslator.fabric;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.LocalConfigSecurity;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.provider.LibreTranslateProvider;
import org.universaltranslator.core.provider.TencentHunyuanProvider;
import org.universaltranslator.core.provider.FallbackTranslationProvider;
import org.universaltranslator.core.provider.LlamaCppOfflineProvider;
import org.universaltranslator.core.provider.OpenAiChatTranslationProvider;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

final class FabricConfig {
    private static final String FILE_NAME = "universal-translator.properties";

    final boolean enabled;
    final boolean translateChat;
    final boolean translateOther;
    final boolean translateOutgoing;
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
    final Path offlineDirectory;
    final boolean diskCache;
    final Path cacheFile;
    private final Path configFile;

    private FabricConfig(Properties properties, Path configFile, Path cacheFile) {
        this.enabled = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
        this.translateChat = Boolean.parseBoolean(properties.getProperty("translate-chat", "true"));
        this.translateOther = Boolean.parseBoolean(properties.getProperty("translate-other", "true"));
        this.translateOutgoing = Boolean.parseBoolean(
                properties.getProperty("translate-outgoing", "false"));
        this.targetLanguage = properties.getProperty("target-language", "zh-CN").trim();
        this.outgoingTargetLanguage = properties.getProperty(
                "outgoing-target-language", "en").trim();
        this.displayMode = TranslationDisplayMode.fromConfig(
                properties.getProperty("display-mode", "translated-only"));
        this.translateEnglishOnly = Boolean.parseBoolean(
                properties.getProperty("translate-english-only", "true"));
        this.translatedTextColor = TranslationTextColor.fromConfig(
                properties.getProperty("translated-text-color", "aqua"));
        this.provider = properties.getProperty("provider", "offline").trim();
        this.endpoint = properties.getProperty(
                "libretranslate-endpoint", "http://127.0.0.1:5000/translate").trim();
        this.apiKey = properties.getProperty("api-key", "").trim();
        this.tencentSecretId = properties.getProperty("tencent-secret-id", "").trim();
        this.tencentSecretKey = properties.getProperty("tencent-secret-key", "").trim();
        this.tencentModel = properties.getProperty(
                "tencent-model", "hunyuan-translation-lite").trim();
        this.llmEndpoint = properties.getProperty(
                "llm-api-endpoint", "http://127.0.0.1:8080/v1/chat/completions").trim();
        this.llmApiKey = properties.getProperty("llm-api-key", "").trim();
        this.llmModel = properties.getProperty("llm-api-model", "local-model").trim();
        this.offlineAutoDownload = Boolean.parseBoolean(
                properties.getProperty("offline-auto-download", "true"));
        this.offlineModel = OfflineModel.fromConfig(properties.getProperty("offline-model", "lite"));
        this.apiFallback = Boolean.parseBoolean(properties.getProperty("api-fallback", "false"));
        this.apiFallbackProvider = properties.getProperty(
                "api-fallback-provider", "libretranslate").trim();
        this.diskCache = Boolean.parseBoolean(properties.getProperty("disk-cache", "true"));
        this.configFile = configFile;
        this.cacheFile = cacheFile;
        this.offlineDirectory = configFile.getParent().resolve("universal-translator-offline");
    }

    static FabricConfig load(Path configDirectory) throws IOException {
        Files.createDirectories(configDirectory);
        Path file = configDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            Properties defaults = defaults();
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                defaults.store(writer,
                        "MC Auto Translation Tool - online translation may send selected game, mod, and modpack text to this endpoint");
            }
        }

        Properties stored = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stored.load(reader);
        }
        Properties properties = defaults();
        properties.putAll(stored);
        boolean legacyMigration = !stored.containsKey("config-version");
        boolean migrated = configVersion(stored) < 3;
        if (legacyMigration) {
            properties.setProperty("display-mode", "translated-only");
            properties.setProperty("translate-english-only", "true");
            properties.setProperty("translated-text-color", "aqua");
        }
        properties.setProperty("config-version", "3");
        LocalConfigSecurity.restrictToOwner(file);
        FabricConfig loaded = new FabricConfig(
                properties, file, configDirectory.resolve("universal-translator-cache.properties"));
        if (migrated) {
            loaded.save();
        }
        return loaded;
    }

    FabricConfig withSettings(
            boolean enabled,
            boolean translateChat,
            boolean translateOther,
            boolean translateOutgoing,
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
            boolean diskCache
    ) {
        Properties properties = toProperties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("translate-chat", Boolean.toString(translateChat));
        properties.setProperty("translate-other", Boolean.toString(translateOther));
        properties.setProperty("translate-outgoing", Boolean.toString(translateOutgoing));
        properties.setProperty("target-language", targetLanguage.trim());
        properties.setProperty("outgoing-target-language", outgoingTargetLanguage.trim());
        properties.setProperty("display-mode", displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                ? "bilingual" : "translated-only");
        properties.setProperty("translate-english-only", Boolean.toString(translateEnglishOnly));
        properties.setProperty("translated-text-color", translatedTextColor.configName());
        properties.setProperty("provider", provider.trim());
        properties.setProperty("libretranslate-endpoint", endpoint.trim());
        properties.setProperty("llm-api-endpoint", llmEndpoint.trim());
        properties.setProperty("llm-api-key", llmApiKey.trim());
        properties.setProperty("llm-api-model", llmModel.trim());
        properties.setProperty("offline-auto-download", Boolean.toString(offlineAutoDownload));
        properties.setProperty("offline-model",
                (offlineModel == null ? OfflineModel.LITE : offlineModel).configName());
        properties.setProperty("api-fallback", Boolean.toString(apiFallback));
        properties.setProperty("disk-cache", Boolean.toString(diskCache));
        return new FabricConfig(properties, configFile, cacheFile);
    }

    FabricConfig withEnabled(boolean enabled) {
        Properties properties = toProperties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        return new FabricConfig(properties, configFile, cacheFile);
    }

    void save() throws IOException {
        Path temporary = configFile.resolveSibling(configFile.getFileName().toString() + ".tmp");
        try {
            Files.deleteIfExists(temporary);
            Files.createFile(temporary);
            LocalConfigSecurity.restrictToOwner(temporary);
            try (Writer writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                toProperties().store(writer,
                        "MC Auto Translation Tool - online translation may send selected game, mod, and modpack text to this endpoint");
            }
            try {
                Files.move(temporary, configFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original save failure, if any.
            }
        }
        LocalConfigSecurity.restrictToOwner(configFile);
    }

    void validateProviderConfiguration() throws Exception {
        TranslationProvider candidate = createProvider();
        if (candidate instanceof AutoCloseable) {
            ((AutoCloseable) candidate).close();
        }
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
                    offlineDirectory, offlineAutoDownload, offlineModel);
            return apiFallback
                    ? new FallbackTranslationProvider(local, createApiProvider(apiFallbackProvider))
                    : local;
        }
        return createApiProvider(provider);
    }

    private TranslationProvider createApiProvider(String selectedProvider) {
        if ("libretranslate".equalsIgnoreCase(selectedProvider)) {
            return new LibreTranslateProvider(endpoint, apiKey);
        }
        if ("tencent-hunyuan".equalsIgnoreCase(selectedProvider)) {
            return new TencentHunyuanProvider(tencentSecretId, tencentSecretKey, tencentModel);
        }
        if ("openai-compatible".equalsIgnoreCase(selectedProvider)) {
            return new OpenAiChatTranslationProvider(
                    llmEndpoint, llmApiKey, llmModel, "openai-compatible");
        }
        throw new IllegalArgumentException("Unsupported translation provider: " + selectedProvider);
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        properties.setProperty("config-version", "3");
        properties.setProperty("enabled", "false");
        properties.setProperty("translate-chat", "true");
        properties.setProperty("translate-other", "true");
        properties.setProperty("translate-outgoing", "false");
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
        return properties;
    }

    private Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("config-version", "3");
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("translate-chat", Boolean.toString(translateChat));
        properties.setProperty("translate-other", Boolean.toString(translateOther));
        properties.setProperty("translate-outgoing", Boolean.toString(translateOutgoing));
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
        return properties;
    }

    private static int configVersion(Properties properties) {
        try {
            return Integer.parseInt(properties.getProperty("config-version", "1").trim());
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }
}
