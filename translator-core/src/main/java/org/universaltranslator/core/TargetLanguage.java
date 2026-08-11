package org.universaltranslator.core;

import java.util.Locale;

/** Shared target-language presets and provider-specific language mappings. */
public final class TargetLanguage {
    public static final String SIMPLIFIED_CHINESE = "zh-CN";
    public static final String TRADITIONAL_CHINESE = "zh-TW";
    public static final String ENGLISH = "en";

    private TargetLanguage() {
    }

    public static String canonicalize(String language) {
        if (language == null) {
            return "";
        }
        String value = language.trim();
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
        if ("zh".equals(normalized) || "zh-cn".equals(normalized)
                || "zh-sg".equals(normalized) || "zh-hans".equals(normalized)) {
            return SIMPLIFIED_CHINESE;
        }
        if ("zh-tw".equals(normalized) || "zh-hk".equals(normalized)
                || "zh-mo".equals(normalized) || "zh-tr".equals(normalized)
                || "zh-hant".equals(normalized)) {
            return TRADITIONAL_CHINESE;
        }
        if ("en".equals(normalized)) {
            return ENGLISH;
        }
        return value;
    }

    public static boolean isSimplifiedChinese(String language) {
        return SIMPLIFIED_CHINESE.equals(canonicalize(language));
    }

    public static boolean isTraditionalChinese(String language) {
        return TRADITIONAL_CHINESE.equals(canonicalize(language));
    }

    public static String nextPreset(String language) {
        String canonical = canonicalize(language);
        if (SIMPLIFIED_CHINESE.equals(canonical)) {
            return TRADITIONAL_CHINESE;
        }
        if (TRADITIONAL_CHINESE.equals(canonical)) {
            return ENGLISH;
        }
        return SIMPLIFIED_CHINESE;
    }

    public static String displayName(String language) {
        String canonical = canonicalize(language);
        if (SIMPLIFIED_CHINESE.equals(canonical)) {
            return "简体中文";
        }
        if (TRADITIONAL_CHINESE.equals(canonical)) {
            return "繁體中文";
        }
        if (ENGLISH.equals(canonical)) {
            return "English";
        }
        return canonical.isEmpty() ? "未设置" : canonical;
    }

    public static String translationInstruction(String language) {
        String canonical = canonicalize(language);
        if (SIMPLIFIED_CHINESE.equals(canonical)) {
            return "Simplified Chinese (zh-CN). Use Simplified Chinese characters";
        }
        if (TRADITIONAL_CHINESE.equals(canonical)) {
            return "Traditional Chinese (Taiwan, zh-TW). Use Traditional Chinese characters";
        }
        return canonical;
    }

    public static String libreTranslateCode(String language) {
        String canonical = canonicalize(language);
        if (canonical.isEmpty()) {
            return canonical;
        }
        if (SIMPLIFIED_CHINESE.equals(canonical)) {
            return "zh";
        }
        if (TRADITIONAL_CHINESE.equals(canonical)) {
            return "zt";
        }
        String normalized = canonical.toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = normalized.indexOf('-');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }
}
