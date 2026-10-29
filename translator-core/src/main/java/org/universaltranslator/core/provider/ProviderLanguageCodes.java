package org.universaltranslator.core.provider;

import org.universaltranslator.core.TargetLanguage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Normalizes Minecraft/user locale values into the common codes expected by each API. */
final class ProviderLanguageCodes {
    /** The languages DeepL documents for both {@code target_lang} and {@code source_lang}. */
    private static final Set<String> DEEPL_LANGUAGES =
            new HashSet<String>(Arrays.asList(
                    "AR", "BG", "CS", "DA", "DE", "EL", "EN", "ES", "ET", "FI", "FR", "HU", "ID",
                    "IT", "JA", "KO", "LT", "LV", "NB", "NL", "PL", "PT", "RO", "RU", "SK", "SL",
                    "SV", "TR", "UK", "ZH"));

    private ProviderLanguageCodes() {
    }

    static String common(String language, boolean allowAuto) {
        if (language == null || language.trim().isEmpty() || TargetLanguage.isAuto(language)) {
            if (allowAuto) {
                return TargetLanguage.AUTO;
            }
            throw new IllegalArgumentException("Target language is required");
        }
        if (TargetLanguage.isSimplifiedChinese(language)) {
            return "zh";
        }
        if (TargetLanguage.isTraditionalChinese(language)) {
            return TargetLanguage.TRADITIONAL_CHINESE;
        }
        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('-');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    static String baidu(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        if (TargetLanguage.TRADITIONAL_CHINESE.equals(common)) return "cht";
        if ("zh".equals(common)) return "zh";
        if ("ja".equals(common)) return "jp";
        if ("ko".equals(common)) return "kor";
        if ("fr".equals(common)) return "fra";
        if ("es".equals(common)) return "spa";
        return common;
    }

    static String tencent(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        return TargetLanguage.TRADITIONAL_CHINESE.equals(common) ? TargetLanguage.TRADITIONAL_CHINESE : common;
    }

    /**
     * Tencent Hunyuan's {@code Target}, which used to be built by its own private copy.
     *
     * <p>That copy answered {@code zh-TR} for Traditional Chinese while {@link #tencent} answered
     * {@code zh-TW} — the same vendor, two codes, so one of them had to be wrong and a wrong one
     * would either 400 every line or quietly translate Traditional Chinese into Simplified. The two
     * products share the TMT code now, and the copy is gone, so they cannot drift apart again.
     */
    static String hunyuan(String language, boolean allowAuto) {
        return tencent(language, allowAuto);
    }

    static String aliyun(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        return TargetLanguage.TRADITIONAL_CHINESE.equals(common) ? "zh-tw" : common;
    }

    static String youdao(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        if ("zh".equals(common)) return "zh-CHS";
        if (TargetLanguage.TRADITIONAL_CHINESE.equals(common)) return "zh-CHT";
        return common;
    }

    static String volcengine(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        return TargetLanguage.TRADITIONAL_CHINESE.equals(common) ? "zh-Hant" : common;
    }

    static String iflytek(String language, boolean allowAuto) {
        String common = common(language, allowAuto);
        if ("zh".equals(common)) return "cn";
        if (TargetLanguage.TRADITIONAL_CHINESE.equals(common)) return "cht";
        return common;
    }

    /**
     * Huawei Cloud NLP's language codes, which are lower case and name Traditional Chinese as
     * {@code zh-tw}.
     *
     * <p>This mapping used to be a private method in the provider, which is why it was the one place
     * that bypassed the shared table: a language the table does not enumerate reached the API
     * unchanged and came back as an {@code unsupported language} 400 that named no field.
     */
    static String huawei(String language, boolean allowAuto) {
        return common(language, allowAuto).toLowerCase(Locale.ROOT);
    }

    /**
     * DeepL's {@code target_lang}, which is upper case and, unlike its {@code source_lang}, has to
     * name a regional variant for the languages that have one.
     */
    static String deeplTarget(String language) {
        String common = common(language, false);
        String code;
        if ("zh".equals(common)) {
            code = "ZH-HANS";
        } else if (TargetLanguage.TRADITIONAL_CHINESE.equals(common)) {
            code = "ZH-HANT";
        } else if ("en".equals(common)) {
            code = "EN-US";
        } else if ("pt".equals(common)) {
            code = "PT-BR";
        } else {
            code = common.toUpperCase(Locale.ROOT);
        }
        requireDeeplLanguage(code);
        return code;
    }

    /**
     * DeepL's {@code source_lang}, which rejects the regional variants its {@code target_lang}
     * requires; an empty result means "let DeepL detect the language", which is also what an
     * omitted parameter does.
     */
    static String deeplSource(String language) {
        if (language == null || language.trim().isEmpty()
                || TargetLanguage.isAuto(language)) {
            return "";
        }
        String common = common(language, false);
        String code;
        if ("zh".equals(common) || TargetLanguage.TRADITIONAL_CHINESE.equals(common)) {
            code = "ZH";
        } else if ("pt".equals(common)) {
            code = "PT";
        } else {
            code = common.toUpperCase(Locale.ROOT);
        }
        requireDeeplLanguage(code);
        return code;
    }

    /**
     * Refuses a language DeepL does not have.
     *
     * <p>Any other value was upper-cased and sent, so a language DeepL does not support produced a
     * {@code 400} on every single line, with the vendor's one-line reply as the only explanation.
     * A rejected language is a configuration mistake, and saying so once is what makes it fixable.
     */
    private static void requireDeeplLanguage(String code) {
        int separator = code.indexOf('-');
        String base = separator < 0 ? code : code.substring(0, separator);
        if (!DEEPL_LANGUAGES.contains(base)) {
            throw new IllegalArgumentException("DeepL does not support the language " + code);
        }
    }
}
