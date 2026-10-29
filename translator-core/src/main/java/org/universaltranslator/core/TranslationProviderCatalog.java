package org.universaltranslator.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Stable provider IDs shared by every Minecraft/loader configuration screen. */
public final class TranslationProviderCatalog {
    /**
     * Every provider in one place: its id, its English display name, and whether the settings screen
     * edits it through the endpoint/key/model editor.
     *
     * <p>These three facts used to live in three separate lists — an id array, a twenty-branch
     * {@code displayName} chain and a nine-branch {@code usesLlmEditor} chain — so adding a provider
     * meant remembering three edits, and forgetting the third one silently shipped a provider that
     * could not be configured. Everything below is derived from this table.
     */
    private static final Provider[] TABLE = {
            new Provider("offline", "Offline", false),
            new Provider("libretranslate", "LibreTranslate", false),
            new Provider("baidu", "Baidu Translate", false),
            new Provider("tencent-tmt", "Tencent TMT", false),
            new Provider("tencent-hunyuan", "Tencent Hunyuan", false),
            new Provider("aliyun-mt", "Alibaba Cloud MT", false),
            new Provider("youdao", "Youdao", false),
            new Provider("volcengine-mt", "Volcengine MT", false),
            new Provider("iflytek-niutrans", "iFlytek NiuTrans", false),
            new Provider("huawei-cloud-mt", "Huawei Cloud MT", false),
            new Provider("deepseek", "DeepSeek", true),
            new Provider("dashscope", "Alibaba Qwen", true),
            new Provider("volcengine-ark", "Volcengine Ark", true),
            new Provider("zhipu", "Zhipu GLM", true),
            new Provider("openai-compatible", "OpenAI Compatible", true),
            new Provider("azure-openai", "Azure OpenAI", true),
            new Provider("deepl", "DeepL", true),
            new Provider("gemini", "Google Gemini", true),
            new Provider("claude", "Anthropic Claude", true),
            new Provider("custom-http-json", "Custom HTTP JSON", false),
    };

    /** The ids in table order, which is the order every screen cycles through. */
    private static final String[] IDS;

    /** Id to row, so lookups on a render path do not walk the table. */
    private static final Map<String, Provider> BY_ID = new HashMap<String, Provider>();

    static {
        IDS = new String[TABLE.length];
        for (int index = 0; index < TABLE.length; index++) {
            IDS[index] = TABLE[index].id;
            BY_ID.put(TABLE[index].id, TABLE[index]);
        }
    }

    private TranslationProviderCatalog() {
    }

    public static String[] values() {
        return IDS.clone();
    }

    public static String next(String current) {
        for (int index = 0; index < IDS.length; index++) {
            if (IDS[index].equalsIgnoreCase(current)) {
                return IDS[(index + 1) % IDS.length];
            }
        }
        // An id that is not in the table — left over from an older version, hand-edited into the
        // config, or removed from the product — used to be answered with "offline", so one press of
        // the switch button silently replaced the user's configured service while its key and
        // endpoint stayed in the config file, unused. The value is now left alone.
        return current;
    }

    public static String displayName(String provider) {
        Provider row = find(provider);
        if (row != null) {
            return row.displayName;
        }
        return provider == null ? "Unknown" : provider;
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
     * what stores them; no settings screen needs a branch of its own. The flag now comes from the
     * table, so a new provider cannot be added without stating whether it needs the editor.
     */
    public static boolean usesLlmEditor(String provider) {
        Provider row = find(provider);
        return row != null && row.llmEditor;
    }

    /** The row for {@code provider}, or {@code null} when the id is unknown. */
    private static Provider find(String provider) {
        if (provider == null) {
            return null;
        }
        return BY_ID.get(provider.trim().toLowerCase(Locale.ROOT));
    }

    /** One row of the catalog. */
    private static final class Provider {
        private final String id;
        private final String displayName;
        private final boolean llmEditor;

        private Provider(String id, String displayName, boolean llmEditor) {
            this.id = id;
            this.displayName = displayName;
            this.llmEditor = llmEditor;
        }
    }
}
