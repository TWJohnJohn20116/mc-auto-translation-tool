package org.universaltranslator.core;

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
            "custom-http-json"
    };

    private TranslationProviderCatalog() {
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
        if ("custom-http-json".equalsIgnoreCase(provider)) return "Custom HTTP JSON";
        return provider;
    }

    public static boolean usesLlmEditor(String provider) {
        return "openai-compatible".equalsIgnoreCase(provider);
    }
}
