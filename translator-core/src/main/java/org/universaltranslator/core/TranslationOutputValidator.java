package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects small-model failure modes before they reach the render cache. */
public final class TranslationOutputValidator {
    private static final Pattern TOKEN = Pattern.compile("__UT_\\d+__", Pattern.CASE_INSENSITIVE);
    private static final String[] INSTRUCTION_FRAGMENTS = {
            "translate minecraft server interface text",
            "minecraft server interface text from",
            "translate the minecraft ui text",
            "translate the user text to",
            "minecraft ui text in zh-cn",
            "return only the translation",
            "reply with only the translation",
            "output translated text only",
            "you are a translation engine",
            "preserve tokens like",
            "keep every __ut_",
            "copy every __ut_",
            "player names, numbers, urls",
            "minecraft formatting markers",
            "urls, punctuation and minecraft",
            "do not explain",
            "never repeat text",
            "游戏服务器界面文本从自动翻译",
            "服务器界面文本从自动翻译",
            "返回的是原文本翻译",
            "只返回译文",
            "仅返回翻译",
            "玩家名、数字、url",
            "minecraft格式标记",
            "不要解释",
            "翻译引擎"
    };

    private TranslationOutputValidator() {
    }

    public static String requireValid(String source, String translated) {
        if (source == null || translated == null || translated.trim().isEmpty()) {
            throw new IllegalArgumentException("Translation output is empty");
        }
        String output = unwrapQuotes(translated.trim());
        int maximumLength = Math.max(48, source.length() * 3 + 24);
        if (output.length() > maximumLength) {
            throw new IllegalArgumentException("Translation output is unexpectedly long");
        }
        if (!source.contains("\n") && (output.contains("\n") || output.contains("\r"))) {
            throw new IllegalArgumentException("Translation output unexpectedly contains multiple lines");
        }
        if (containsInstructionArtifact(output)) {
            throw new IllegalArgumentException("Translation output echoed its instructions");
        }
        if (!tokenSequence(source).equals(tokenSequence(output))) {
            throw new IllegalArgumentException("Translation output changed or reordered protected tokens");
        }
        return output;
    }

    /** Validates the restored text immediately before it can enter a render cache. */
    public static String requireDisplaySafe(String source, String restored) {
        if (source == null || restored == null || restored.trim().isEmpty()) {
            throw new IllegalArgumentException("Restored translation output is empty");
        }
        String output = restored.trim();
        int maximumLength = Math.max(48, source.length() * 3 + 24);
        if (output.length() > maximumLength) {
            throw new IllegalArgumentException("Restored translation output is unexpectedly long");
        }
        if (!source.contains("\n") && (output.contains("\n") || output.contains("\r"))) {
            throw new IllegalArgumentException("Restored translation unexpectedly contains multiple lines");
        }
        if (TOKEN.matcher(output).find()) {
            throw new IllegalArgumentException("Restored translation leaked an internal placeholder");
        }
        if (containsInstructionArtifact(output)) {
            throw new IllegalArgumentException("Restored translation echoed its instructions");
        }
        return output;
    }

    /** Prevents leaked model instructions/placeholders from being translated recursively. */
    public static boolean containsInternalArtifact(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return TOKEN.matcher(text).find() || containsInstructionArtifact(text);
    }

    private static boolean containsInstructionArtifact(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("<|system|>")
                || normalized.contains("<|assistant|>")
                || normalized.contains("[quote]")) {
            return true;
        }
        for (String fragment : INSTRUCTION_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokenSequence(String text) {
        List<String> tokens = new ArrayList<String>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static String unwrapQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '\u201c' && last == '\u201d')) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }
}
