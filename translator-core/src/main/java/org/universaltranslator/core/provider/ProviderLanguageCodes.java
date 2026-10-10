package org.universaltranslator.core.provider;

import org.universaltranslator.core.TargetLanguage;

import java.util.Locale;

/** Normalizes Minecraft/user locale values into the common codes expected by each API. */
final class ProviderLanguageCodes {
    private ProviderLanguageCodes() {
    }

    static String common(String language, boolean allowAuto) {
        if (language == null || language.trim().isEmpty() || "auto".equalsIgnoreCase(language.trim())) {
            if (allowAuto) {
                return "auto";
            }
            throw new IllegalArgumentException("Target language is required");
        }
        if (TargetLanguage.isSimplifiedChinese(language)) {
            return "zh";
        }
        if (TargetLanguage.isTraditionalChinese(language)) {
            return "zh-TW";
        }
        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('-');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    static String baidu(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        if ("zh-TW".equals(common)) return "cht";
        if ("zh".equals(common)) return "zh";
        if ("ja".equals(common)) return "jp";
        if ("ko".equals(common)) return "kor";
        if ("fr".equals(common)) return "fra";
        if ("es".equals(common)) return "spa";
        return common;
    }

    static String tencent(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        return "zh-TW".equals(common) ? "zh-TW" : common;
    }

    static String aliyun(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        return "zh-TW".equals(common) ? "zh-tw" : common;
    }

    static String youdao(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        if ("zh".equals(common)) return "zh-CHS";
        if ("zh-TW".equals(common)) return "zh-CHT";
        return common;
    }

    static String volcengine(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        return "zh-TW".equals(common) ? "zh-Hant" : common;
    }

    static String iflytek(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        if ("zh".equals(common)) return "cn";
        if ("zh-TW".equals(common)) return "cht";
        return common;
    }

    /**
     * DeepL's {@code target_lang}, which is upper case and, unlike its {@code source_lang}, has to
     * name a regional variant for the languages that have one.
     */
    static String deeplTarget(String language) {
        String common = common(language, false);
        if ("zh".equals(common)) return "ZH-HANS";
        if ("zh-TW".equals(common)) return "ZH-HANT";
        if ("en".equals(common)) return "EN-US";
        if ("pt".equals(common)) return "PT-BR";
        return common.toUpperCase(Locale.ROOT);
    }

    /**
     * DeepL's {@code source_lang}, which rejects the regional variants its {@code target_lang}
     * requires; an empty result means "let DeepL detect the language", which is also what an
     * omitted parameter does.
     */
    static String deeplSource(String language) {
        if (language == null || language.trim().isEmpty()
                || "auto".equalsIgnoreCase(language.trim())) {
            return "";
        }
        String common = common(language, false);
        if ("zh".equals(common) || "zh-TW".equals(common)) return "ZH";
        if ("pt".equals(common)) return "PT";
        return common.toUpperCase(Locale.ROOT);
    }
}
