package org.universaltranslator.core;

import java.util.Locale;

/** Shared target-language presets and provider-specific language mappings. */
public final class TargetLanguage {
    public static final String SIMPLIFIED_CHINESE = "zh-CN";
    public static final String TRADITIONAL_CHINESE = "zh-TW";
    public static final String ENGLISH = "en";
    public static final String JAPANESE = "ja";
    public static final String KOREAN = "ko";
    public static final String FRENCH = "fr";
    public static final String GERMAN = "de";
    public static final String SPANISH = "es";
    public static final String PORTUGUESE = "pt";
    public static final String RUSSIAN = "ru";

    private static final String[] PRESETS = {
            SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ENGLISH, JAPANESE, KOREAN,
            FRENCH, GERMAN, SPANISH, PORTUGUESE, RUSSIAN
    };

    private TargetLanguage() {
    }

    public static String[] presets() {
        return PRESETS.clone();
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
        for (String preset : PRESETS) {
            if (preset.equalsIgnoreCase(normalized)
                    || normalized.startsWith(preset.toLowerCase(Locale.ROOT) + "-")) {
                return preset;
            }
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
        for (int index = 0; index < PRESETS.length; index++) {
            if (PRESETS[index].equals(canonical)) {
                return PRESETS[(index + 1) % PRESETS.length];
            }
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
        if (JAPANESE.equals(canonical)) {
            return "日本語";
        }
        if (KOREAN.equals(canonical)) {
            return "한국어";
        }
        if (FRENCH.equals(canonical)) {
            return "Français";
        }
        if (GERMAN.equals(canonical)) {
            return "Deutsch";
        }
        if (SPANISH.equals(canonical)) {
            return "Español";
        }
        if (PORTUGUESE.equals(canonical)) {
            return "Português";
        }
        if (RUSSIAN.equals(canonical)) {
            return "Русский";
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
        if (ENGLISH.equals(canonical)) return "English (en)";
        if (JAPANESE.equals(canonical)) return "Japanese (ja)";
        if (KOREAN.equals(canonical)) return "Korean (ko)";
        if (FRENCH.equals(canonical)) return "French (fr)";
        if (GERMAN.equals(canonical)) return "German (de)";
        if (SPANISH.equals(canonical)) return "Spanish (es)";
        if (PORTUGUESE.equals(canonical)) return "Portuguese (pt)";
        if (RUSSIAN.equals(canonical)) return "Russian (ru)";
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
