package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationProviderCatalog;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.net.HttpJsonClient;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Immutable, whitelisted online-provider settings shared by every loader/version. */
public final class OnlineProviderConfig {
    private static final String CUSTOM_HEADER_PREFIX = "custom-api-header.";
    /**
     * Keys edited by the in-game advanced panel rather than by a settings screen.
     *
     * <p>They are re-read from disk before every save (see {@link #preservePromptSettings}) so that
     * pressing Save on a settings screen that was opened before the panel ran cannot write a stale
     * snapshot back over them.
     */
    private static final String[] PROMPT_KEYS = {"translation-quality", "custom-system-prompt"};
    private static final String[] KEYS = {
            "libretranslate-endpoint", "api-key",
            "tencent-secret-id", "tencent-secret-key", "tencent-model",
            "llm-api-endpoint", "llm-api-key", "llm-api-model",
            "translation-quality", "custom-system-prompt",
            "api-connect-timeout-ms", "api-read-timeout-ms", "api-max-attempts",
            "api-min-request-interval-ms",
            "baidu-endpoint", "baidu-app-id", "baidu-secret",
            "tencent-tmt-endpoint", "tencent-tmt-secret-id", "tencent-tmt-secret-key",
            "aliyun-mt-endpoint", "aliyun-access-key-id", "aliyun-access-key-secret",
            "youdao-endpoint", "youdao-app-key", "youdao-secret", "youdao-vocab-id",
            "volcengine-mt-endpoint", "volcengine-access-key", "volcengine-secret-key",
            "volcengine-region",
            "iflytek-endpoint", "iflytek-app-id", "iflytek-api-key", "iflytek-api-secret",
            "huawei-endpoint", "huawei-project-id", "huawei-auth-token",
            "deepseek-endpoint", "deepseek-api-key", "deepseek-model",
            "dashscope-endpoint", "dashscope-api-key", "dashscope-model",
            "volcengine-ark-endpoint", "volcengine-ark-api-key", "volcengine-ark-model",
            "zhipu-endpoint", "zhipu-api-key", "zhipu-model",
            "azure-openai-endpoint", "azure-openai-api-key", "azure-openai-model",
            "azure-openai-api-version",
            "deepl-endpoint", "deepl-api-key", "deepl-model",
            "gemini-endpoint", "gemini-api-key", "gemini-model",
            "claude-endpoint", "claude-api-key", "claude-model",
            "custom-api-endpoint", "custom-api-method", "custom-api-content-type",
            "custom-api-key", "custom-api-auth-header", "custom-api-auth-prefix",
            "custom-api-request-template", "custom-api-response-path"
    };

    private final Properties values;

    /** Three fields shared by OpenAI-compatible provider configuration screens. */
    public static final class LlmEditorSettings {
        private final String endpoint;
        private final String apiKey;
        private final String model;

        private LlmEditorSettings(String endpoint, String apiKey, String model) {
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.model = model;
        }

        public String endpoint() {
            return endpoint;
        }

        public String apiKey() {
            return apiKey;
        }

        public String model() {
            return model;
        }
    }

    private OnlineProviderConfig(Properties values) {
        this.values = values;
    }

    public static OnlineProviderConfig from(Properties source) {
        Properties copy = new Properties();
        applyDefaults(copy);
        if (source != null) {
            for (String key : KEYS) {
                if (source.containsKey(key)) {
                    copy.setProperty(key, source.getProperty(key, ""));
                }
            }
            for (String key : source.stringPropertyNames()) {
                if (key.startsWith(CUSTOM_HEADER_PREFIX)) {
                    copy.setProperty(key, source.getProperty(key, ""));
                }
            }
        }
        return new OnlineProviderConfig(copy);
    }

    public static void applyDefaults(Properties properties) {
        putDefault(properties, "libretranslate-endpoint", "http://127.0.0.1:5000/translate");
        putDefault(properties, "api-key", "");
        putDefault(properties, "tencent-secret-id", "");
        putDefault(properties, "tencent-secret-key", "");
        putDefault(properties, "tencent-model", "hunyuan-translation-lite");
        putDefault(properties, "llm-api-endpoint", "http://127.0.0.1:8080/v1/chat/completions");
        putDefault(properties, "llm-api-key", "");
        putDefault(properties, "llm-api-model", "local-model");
        putDefault(properties, "translation-quality", TranslationQuality.DEFAULT.configName());
        putDefault(properties, "custom-system-prompt", "");
        putDefault(properties, "api-connect-timeout-ms", "5000");
        putDefault(properties, "api-read-timeout-ms", "120000");
        putDefault(properties, "api-max-attempts", "3");
        putDefault(properties, "api-min-request-interval-ms", "60");

        putDefault(properties, "baidu-endpoint", "https://fanyi-api.baidu.com/api/trans/vip/translate");
        putDefault(properties, "baidu-app-id", "");
        putDefault(properties, "baidu-secret", "");
        putDefault(properties, "tencent-tmt-endpoint", "https://tmt.tencentcloudapi.com/");
        putDefault(properties, "tencent-tmt-secret-id", "");
        putDefault(properties, "tencent-tmt-secret-key", "");
        putDefault(properties, "aliyun-mt-endpoint",
                "https://mt.cn-hangzhou.aliyuncs.com/api/translate/web/general");
        putDefault(properties, "aliyun-access-key-id", "");
        putDefault(properties, "aliyun-access-key-secret", "");
        putDefault(properties, "youdao-endpoint", "https://openapi.youdao.com/api");
        putDefault(properties, "youdao-app-key", "");
        putDefault(properties, "youdao-secret", "");
        putDefault(properties, "youdao-vocab-id", "");
        putDefault(properties, "volcengine-mt-endpoint", "https://translate.volcengineapi.com/");
        putDefault(properties, "volcengine-access-key", "");
        putDefault(properties, "volcengine-secret-key", "");
        putDefault(properties, "volcengine-region", "cn-north-1");
        putDefault(properties, "iflytek-endpoint", "https://ntrans.xfyun.cn/v2/ots");
        putDefault(properties, "iflytek-app-id", "");
        putDefault(properties, "iflytek-api-key", "");
        putDefault(properties, "iflytek-api-secret", "");
        putDefault(properties, "huawei-endpoint", "https://nlp-ext.cn-north-4.myhuaweicloud.com");
        putDefault(properties, "huawei-project-id", "");
        putDefault(properties, "huawei-auth-token", "");

        putDefault(properties, "deepseek-endpoint", "https://api.deepseek.com/chat/completions");
        putDefault(properties, "deepseek-api-key", "");
        putDefault(properties, "deepseek-model", "deepseek-v4-flash");
        putDefault(properties, "dashscope-endpoint",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        putDefault(properties, "dashscope-api-key", "");
        putDefault(properties, "dashscope-model", "qwen-plus");
        putDefault(properties, "volcengine-ark-endpoint",
                "https://ark.cn-beijing.volces.com/api/v3/chat/completions");
        putDefault(properties, "volcengine-ark-api-key", "");
        putDefault(properties, "volcengine-ark-model", "");
        putDefault(properties, "zhipu-endpoint",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions");
        putDefault(properties, "zhipu-api-key", "");
        putDefault(properties, "zhipu-model", "glm-5.2");

        putDefault(properties, "azure-openai-endpoint", AzureOpenAiTranslationProvider.DEFAULT_ENDPOINT);
        putDefault(properties, "azure-openai-api-key", "");
        // The deployment name is the user's own, so there is no useful default to ship.
        putDefault(properties, "azure-openai-model", "");
        putDefault(properties, "azure-openai-api-version",
                AzureOpenAiTranslationProvider.DEFAULT_API_VERSION);
        putDefault(properties, "deepl-endpoint", DeepLTranslationProvider.DEFAULT_ENDPOINT);
        putDefault(properties, "deepl-api-key", "");
        // This field is DeepL's model_type, not a model name. The mod is latency sensitive, and the
        // settings screen's connection test needs a non-empty value, so the latency variant ships as
        // the default; "quality_optimized" or an empty value are both accepted.
        putDefault(properties, "deepl-model", "latency_optimized");
        putDefault(properties, "gemini-endpoint", GeminiTranslationProvider.DEFAULT_ENDPOINT);
        putDefault(properties, "gemini-api-key", "");
        putDefault(properties, "gemini-model", "gemini-2.5-flash");
        putDefault(properties, "claude-endpoint", ClaudeTranslationProvider.DEFAULT_ENDPOINT);
        putDefault(properties, "claude-api-key", "");
        putDefault(properties, "claude-model", "claude-sonnet-4-5");

        putDefault(properties, "custom-api-endpoint", "http://127.0.0.1:5000/translate");
        putDefault(properties, "custom-api-method", "POST");
        putDefault(properties, "custom-api-content-type", "application/json; charset=utf-8");
        putDefault(properties, "custom-api-key", "");
        putDefault(properties, "custom-api-auth-header", "Authorization");
        putDefault(properties, "custom-api-auth-prefix", "Bearer ");
        putDefault(properties, "custom-api-request-template",
                "{\"text\":${textJson},\"source\":${sourceJson},\"target\":${targetJson}}");
        putDefault(properties, "custom-api-response-path", "translatedText");
    }

    public void writeTo(Properties target) {
        for (String key : KEYS) {
            target.setProperty(key, values.getProperty(key, ""));
        }
        for (String key : values.stringPropertyNames()) {
            if (key.startsWith(CUSTOM_HEADER_PREFIX)) {
                target.setProperty(key, values.getProperty(key, ""));
            }
        }
    }

    /** Returns the independently stored editor values for the selected LLM provider. */
    public LlmEditorSettings llmEditorSettings(String provider) {
        String[] keys = llmEditorKeys(provider);
        return new LlmEditorSettings(value(keys[0]), value(keys[1]), value(keys[2]));
    }

    /** Selected translation quality mode; an unknown value falls back to the historical prompt. */
    public TranslationQuality translationQuality() {
        return TranslationQuality.fromConfig(value("translation-quality"));
    }

    /** Custom system prompt, or an empty string to use the built-in prompt of the active mode. */
    public String customSystemPrompt() {
        return value("custom-system-prompt");
    }

    /** The prompt configuration every chat-completion provider built from here receives. */
    public TranslationPrompt.Settings promptSettings() {
        return new TranslationPrompt.Settings(translationQuality(), customSystemPrompt());
    }

    /**
     * Writes the prompt settings, leaving every other key untouched.
     *
     * <p>Both keys are part of {@link #KEYS}, so they survive the round trip through the platform
     * configuration classes' {@code toProperties()}/{@code withSettings(...)} path without those
     * classes having to know about them.
     */
    public static void applyPromptSettings(
            Properties target,
            TranslationQuality quality,
            String customSystemPrompt
    ) {
        if (target == null) {
            throw new IllegalArgumentException("Target properties are required");
        }
        target.setProperty("translation-quality",
                (quality == null ? TranslationQuality.DEFAULT : quality).configName());
        target.setProperty("custom-system-prompt",
                customSystemPrompt == null ? "" : customSystemPrompt.trim());
    }

    /**
     * Copies the advanced settings that are currently stored on disk into {@code target}.
     *
     * <p>They are edited by the diagnostics screen, which can run while a settings screen is still
     * open holding an older snapshot of the whole file. Without this re-read, pressing Save there
     * would write that stale snapshot back and silently undo the change. Every other key remains
     * owned by the settings screen, so only {@link #PROMPT_KEYS} are re-read.
     *
     * @param configFile configuration file to read; {@code null} does nothing
     * @param target properties about to be written back
     */
    public static void preservePromptSettings(Path configFile, Properties target) {
        if (configFile == null || target == null) {
            return;
        }
        Properties stored = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            stored.load(reader);
        } catch (IOException unreadable) {
            // A file that cannot be read is not a reason to fail a save; the snapshot is written as
            // it stands, exactly as it was before this method existed.
            return;
        } catch (RuntimeException malformed) {
            return;
        }
        for (String key : PROMPT_KEYS) {
            if (stored.containsKey(key)) {
                target.setProperty(key, stored.getProperty(key, ""));
            }
        }
    }

    /**
     * Writes only the selected provider's LLM editor values, preserving every other credential.
     *
     * <p>Providers that do not use the LLM editor (Baidu, Youdao, offline, custom HTTP JSON, ...) are
     * skipped entirely: the settings screen keeps the last loaded editor values in its fields when such a
     * provider is selected, so writing them here would clobber the generic {@code llm-api-*} keys that
     * back {@code openai-compatible} / local llama.cpp with another provider's endpoint, key and model.</p>
     */
    public static void applyLlmEditorSettings(
            Properties target,
            String provider,
            String endpoint,
            String apiKey,
            String model
    ) {
        if (target == null) {
            throw new IllegalArgumentException("Target properties are required");
        }
        if (!TranslationProviderCatalog.usesLlmEditor(provider)) {
            return;
        }
        String[] keys = llmEditorKeys(provider);
        target.setProperty(keys[0], clean(endpoint));
        target.setProperty(keys[1], clean(apiKey));
        target.setProperty(keys[2], clean(model));
    }

    public TranslationProvider create(String provider) {
        String selected = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        HttpJsonClient http = new HttpJsonClient(
                integer("api-connect-timeout-ms", 5000, 250, 60000),
                integer("api-read-timeout-ms", 120000, 1000, 300000));
        TranslationProvider raw;
        if ("libretranslate".equals(selected)) {
            raw = new LibreTranslateProvider(value("libretranslate-endpoint"), value("api-key"), http);
        } else if ("tencent-hunyuan".equals(selected)) {
            raw = new TencentHunyuanProvider(value("tencent-secret-id"),
                    value("tencent-secret-key"), value("tencent-model"), http);
        } else if ("baidu".equals(selected)) {
            raw = new BaiduTranslationProvider(value("baidu-endpoint"), value("baidu-app-id"),
                    value("baidu-secret"), http);
        } else if ("tencent-tmt".equals(selected)) {
            raw = new TencentTmtProvider(value("tencent-tmt-endpoint"),
                    value("tencent-tmt-secret-id"), value("tencent-tmt-secret-key"), http);
        } else if ("aliyun-mt".equals(selected)) {
            raw = new AliyunMachineTranslationProvider(value("aliyun-mt-endpoint"),
                    value("aliyun-access-key-id"), value("aliyun-access-key-secret"), http);
        } else if ("youdao".equals(selected)) {
            raw = new YoudaoTranslationProvider(value("youdao-endpoint"), value("youdao-app-key"),
                    value("youdao-secret"), value("youdao-vocab-id"), http);
        } else if ("volcengine-mt".equals(selected)) {
            raw = new VolcengineMachineTranslationProvider(value("volcengine-mt-endpoint"),
                    value("volcengine-access-key"), value("volcengine-secret-key"),
                    value("volcengine-region"), http);
        } else if ("iflytek-niutrans".equals(selected)) {
            raw = new IflytekNiuTransProvider(value("iflytek-endpoint"), value("iflytek-app-id"),
                    value("iflytek-api-key"), value("iflytek-api-secret"), http);
        } else if ("huawei-cloud-mt".equals(selected)) {
            raw = new HuaweiCloudTranslationProvider(value("huawei-endpoint"),
                    value("huawei-project-id"), value("huawei-auth-token"), http);
        } else if ("deepseek".equals(selected)) {
            raw = openAi("deepseek", "deepseek-endpoint", "deepseek-api-key", "deepseek-model", http);
        } else if ("dashscope".equals(selected)) {
            raw = openAi("dashscope", "dashscope-endpoint", "dashscope-api-key", "dashscope-model", http);
        } else if ("volcengine-ark".equals(selected)) {
            raw = openAi("volcengine-ark", "volcengine-ark-endpoint",
                    "volcengine-ark-api-key", "volcengine-ark-model", http);
        } else if ("zhipu".equals(selected)) {
            raw = openAi("zhipu", "zhipu-endpoint", "zhipu-api-key", "zhipu-model", http);
        } else if ("openai-compatible".equals(selected)) {
            raw = openAi("openai-compatible", "llm-api-endpoint", "llm-api-key", "llm-api-model", http);
        } else if ("azure-openai".equals(selected)) {
            raw = new AzureOpenAiTranslationProvider(value("azure-openai-endpoint"),
                    value("azure-openai-api-key"), value("azure-openai-model"),
                    value("azure-openai-api-version"), http, promptSettings());
        } else if ("deepl".equals(selected)) {
            raw = new DeepLTranslationProvider(value("deepl-endpoint"), value("deepl-api-key"),
                    value("deepl-model"), http);
        } else if ("gemini".equals(selected)) {
            raw = new GeminiTranslationProvider(value("gemini-endpoint"), value("gemini-api-key"),
                    value("gemini-model"), http, promptSettings());
        } else if ("claude".equals(selected)) {
            raw = new ClaudeTranslationProvider(value("claude-endpoint"), value("claude-api-key"),
                    value("claude-model"), http, promptSettings());
        } else if ("custom-http-json".equals(selected)) {
            raw = custom(http);
        } else {
            throw new IllegalArgumentException("Unsupported online translation provider: " + provider);
        }
        return new ResilientTranslationProvider(raw,
                integer("api-max-attempts", 3, 1, 5),
                integer("api-min-request-interval-ms", 60, 0, 60000));
    }

    private TranslationProvider openAi(
            String providerId,
            String endpointKey,
            String apiKeyKey,
            String modelKey,
            HttpJsonClient http
    ) {
        String apiKey = value(apiKeyKey);
        if (!"openai-compatible".equals(providerId)) {
            ProviderSupport.requireCredential(providerId + " API key", apiKey);
        }
        return new OpenAiChatTranslationProvider(
                value(endpointKey), apiKey, value(modelKey), providerId, http, promptSettings());
    }

    private TranslationProvider custom(HttpJsonClient http) {
        String apiKey = value("custom-api-key");
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String authHeader = value("custom-api-auth-header");
        if (!apiKey.isEmpty() && !authHeader.isEmpty()) {
            headers.put(authHeader, rawValue("custom-api-auth-prefix") + "${apiKey}");
        }
        for (String key : values.stringPropertyNames()) {
            if (key.startsWith(CUSTOM_HEADER_PREFIX) && key.length() > CUSTOM_HEADER_PREFIX.length()) {
                headers.put(key.substring(CUSTOM_HEADER_PREFIX.length()), values.getProperty(key, ""));
            }
        }
        return new CustomHttpJsonTranslationProvider(
                value("custom-api-endpoint"), value("custom-api-method"),
                value("custom-api-content-type"), value("custom-api-request-template"),
                value("custom-api-response-path"), headers, apiKey, http);
    }

    private String value(String key) {
        return values.getProperty(key, "").trim();
    }

    private String rawValue(String key) {
        return values.getProperty(key, "");
    }

    private int integer(String key, int fallback, int minimum, int maximum) {
        int parsed;
        try {
            parsed = Integer.parseInt(value(key));
        } catch (NumberFormatException ignored) {
            parsed = fallback;
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static void putDefault(Properties properties, String key, String value) {
        if (!properties.containsKey(key)) {
            properties.setProperty(key, value);
        }
    }

    private static String[] llmEditorKeys(String provider) {
        String selected = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if ("deepseek".equals(selected)) {
            return new String[]{"deepseek-endpoint", "deepseek-api-key", "deepseek-model"};
        }
        if ("dashscope".equals(selected)) {
            return new String[]{"dashscope-endpoint", "dashscope-api-key", "dashscope-model"};
        }
        if ("volcengine-ark".equals(selected)) {
            return new String[]{"volcengine-ark-endpoint", "volcengine-ark-api-key", "volcengine-ark-model"};
        }
        if ("zhipu".equals(selected)) {
            return new String[]{"zhipu-endpoint", "zhipu-api-key", "zhipu-model"};
        }
        if ("azure-openai".equals(selected)) {
            return new String[]{"azure-openai-endpoint", "azure-openai-api-key",
                    "azure-openai-model"};
        }
        if ("deepl".equals(selected)) {
            return new String[]{"deepl-endpoint", "deepl-api-key", "deepl-model"};
        }
        if ("gemini".equals(selected)) {
            return new String[]{"gemini-endpoint", "gemini-api-key", "gemini-model"};
        }
        if ("claude".equals(selected)) {
            return new String[]{"claude-endpoint", "claude-api-key", "claude-model"};
        }
        // Keep the historical generic values available while offline or another provider is selected.
        return new String[]{"llm-api-endpoint", "llm-api-key", "llm-api-model"};
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
