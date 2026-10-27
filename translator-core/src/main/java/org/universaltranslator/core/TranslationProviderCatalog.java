package org.universaltranslator.core;

import java.util.Locale;
import java.util.function.Function;

/** Stable provider IDs shared by every Minecraft/loader configuration screen. */
public final class TranslationProviderCatalog {
    private static final String[] PROVIDERS = {
            "offline",
            "libretranslate",
            "baidu",
            "tencent-tmt",
            "tencent-hunyuan",
            "aliyun-mt",
            "youdao",
            "volcengine-mt",
            "iflytek-niutrans",
            "huawei-cloud-mt",
            "deepseek",
            "dashscope",
            "volcengine-ark",
            "zhipu",
            "openai-compatible",
            "azure-openai",
            "deepl",
            "gemini",
            "claude",
            "custom-http-json"
    };

    private TranslationProviderCatalog() {
    }

    public static String[] values() {
        return PROVIDERS.clone();
    }

    public static String next(String current) {
        for (int index = 0; index < PROVIDERS.length; index++) {
            if (PROVIDERS[index].equalsIgnoreCase(current)) {
                return PROVIDERS[(index + 1) % PROVIDERS.length];
            }
        }
        return "offline";
    }

    public static String displayName(String provider) {
        if (provider == null) return "Unknown";
        if ("offline".equalsIgnoreCase(provider)) return "Offline";
        if ("libretranslate".equalsIgnoreCase(provider)) return "LibreTranslate";
        if ("baidu".equalsIgnoreCase(provider)) return "Baidu Translate";
        if ("tencent-tmt".equalsIgnoreCase(provider)) return "Tencent TMT";
        if ("tencent-hunyuan".equalsIgnoreCase(provider)) return "Tencent Hunyuan";
        if ("aliyun-mt".equalsIgnoreCase(provider)) return "Alibaba Cloud MT";
        if ("youdao".equalsIgnoreCase(provider)) return "Youdao";
        if ("volcengine-mt".equalsIgnoreCase(provider)) return "Volcengine MT";
        if ("iflytek-niutrans".equalsIgnoreCase(provider)) return "iFlytek NiuTrans";
        if ("huawei-cloud-mt".equalsIgnoreCase(provider)) return "Huawei Cloud MT";
        if ("deepseek".equalsIgnoreCase(provider)) return "DeepSeek";
        if ("dashscope".equalsIgnoreCase(provider)) return "Alibaba Qwen";
        if ("volcengine-ark".equalsIgnoreCase(provider)) return "Volcengine Ark";
        if ("zhipu".equalsIgnoreCase(provider)) return "Zhipu GLM";
        if ("openai-compatible".equalsIgnoreCase(provider)) return "OpenAI Compatible";
        if ("azure-openai".equalsIgnoreCase(provider)) return "Azure OpenAI";
        if ("deepl".equalsIgnoreCase(provider)) return "DeepL";
        if ("gemini".equalsIgnoreCase(provider)) return "Google Gemini";
        if ("claude".equalsIgnoreCase(provider)) return "Anthropic Claude";
        if ("custom-http-json".equalsIgnoreCase(provider)) return "Custom HTTP JSON";
        return provider;
    }

    /**
     * Language key holding this provider's display name.
     *
     * <p>{@link #displayName(String)} returned an English literal, so "Tencent Hunyuan" and "Anthropic
     * Claude" appeared in Chinese and Japanese interfaces too — the only untranslated block left in the
     * settings screen. Keys are derived from the id so a new provider needs one language entry, not a
     * branch in a screen.
     */
    public static String displayNameKey(String provider) {
        String id = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return "provider.universal_translator.unknown";
        }
        return "provider.universal_translator." + id.replace('-', '_');
    }

    /**
     * Display name localized through {@code localizer} (the screen's {@code tr} adapter).
     *
     * <p>Falls back to {@link #displayName(String)} when the language file has no entry, which happens
     * for an unknown id or a language file older than the provider — the id is still shown instead of a
     * raw key like {@code provider.universal_translator.foo}.
     */
    public static String displayName(String provider, Function<String, String> localizer) {
        String english = displayName(provider);
        if (localizer == null) {
            return english;
        }
        String key = displayNameKey(provider);
        String localized;
        try {
            localized = localizer.apply(key);
        } catch (RuntimeException error) {
            return english;
        }
        return localized == null || localized.isEmpty() || localized.equals(key) ? english : localized;
    }

    /**
     * Whether the provider is configured through the endpoint/key/model editor.
     *
     * <p>Azure OpenAI, DeepL, Gemini and Claude all have those three values (the "model" being the
     * Azure deployment name for Azure, and DeepL's {@code model_type} for DeepL), so the editor is
     * what stores them; no settings screen needs a branch of its own.
     */
    public static boolean usesLlmEditor(String provider) {
        return "deepseek".equalsIgnoreCase(provider)
                || "dashscope".equalsIgnoreCase(provider)
                || "volcengine-ark".equalsIgnoreCase(provider)
                || "zhipu".equalsIgnoreCase(provider)
                || "openai-compatible".equalsIgnoreCase(provider)
                || "azure-openai".equalsIgnoreCase(provider)
                || "deepl".equalsIgnoreCase(provider)
                || "gemini".equalsIgnoreCase(provider)
                || "claude".equalsIgnoreCase(provider);
    }
}
