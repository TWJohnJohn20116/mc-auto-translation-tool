package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Comparator;

/**
 * Replaces values that should survive translation verbatim with stable ASCII tokens.
 * This is especially important for rapidly changing scoreboards.
 */
public final class ProtectedText {
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)";
    private static final String IPV4_SOURCE =
            "(?<![A-Za-z0-9_.-])" + IPV4_OCTET + "(?:\\." + IPV4_OCTET + "){3}"
                    + "(?::\\d{1,5})?(?![A-Za-z0-9_.-])";
    private static final String BRACKETED_IPV6_SOURCE =
            "\\[(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}\\](?::\\d{1,5})?";
    private static final String RAW_IPV6_SOURCE =
            "(?<![A-Za-z0-9_])(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{0,4}"
                    + "(?:%[A-Za-z0-9_.-]+)?(?![A-Za-z0-9_])";
    private static final String DOMAIN_LABEL =
            "(?:_?[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)";
    private static final String DOMAIN_SOURCE =
            "(?<![A-Za-z0-9_.-])(?:" + DOMAIN_LABEL + "\\.)+[A-Za-z]{2,63}"
                    + "(?::\\d{1,5})?(?![A-Za-z0-9_.-])";
    private static final String LOCALHOST_SOURCE =
            "(?<![A-Za-z0-9_.-])localhost(?::\\d{1,5})?(?![A-Za-z0-9_.-])";
    private static final String PROTECTED_SOURCE =
            "(?:\\u00a7[0-9A-FK-ORa-fk-or])" +
            "|(?:https?://\\S+|www\\.\\S+)" +
            "|(?:" + BRACKETED_IPV6_SOURCE + "|" + IPV4_SOURCE + "|" + RAW_IPV6_SOURCE
                    + "|" + DOMAIN_SOURCE + "|" + LOCALHOST_SOURCE + ")" +
            "|(?:(?<![A-Za-z0-9_])(?:\\d{1,3}(?:[.,]\\d{3})+|\\d+(?:[.,]\\d+)?)(?:%|ms|s|m|h|d)?(?![A-Za-z0-9_]))" +
            "|(?:%[A-Za-z0-9_.:-]+%)" +
            "|(?:\\{[A-Za-z0-9_.:-]+})";
    private static final String HAN_SOURCE =
            "(?:[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]+)";
    private static final Pattern PROTECTED = Pattern.compile(PROTECTED_SOURCE);
    private static final Pattern PROTECTED_WITH_HAN =
            Pattern.compile("(?:" + PROTECTED_SOURCE + "|" + HAN_SOURCE + ")");
    private static final Pattern INTERNAL_TOKEN = Pattern.compile("__UT_\\d+__");

    private final String original;
    private final String template;
    private final List<String> values;

    private ProtectedText(String original, String template, List<String> values) {
        this.original = original;
        this.template = template;
        this.values = Collections.unmodifiableList(values);
    }

    public static ProtectedText parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }

        return parseWithPattern(text, PROTECTED);
    }

    public static ProtectedText parse(String text, Iterable<String> protectedLiterals) {
        return parse(text, protectedLiterals, false);
    }

    /**
     * Parses protected values and optionally protects existing Han text. Protecting Han text
     * lets mixed Chinese/English labels translate only their English portion.
     */
    public static ProtectedText parse(
            String text,
            Iterable<String> protectedLiterals,
            boolean preserveHanText
    ) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        List<String> literals = new ArrayList<String>();
        if (protectedLiterals != null) {
            for (String literal : protectedLiterals) {
                if (literal != null && !literal.isEmpty() && literals.size() < 1000) {
                    literals.add(literal);
                }
            }
        }
        if (literals.isEmpty()) {
            return parseWithPattern(text, preserveHanText ? PROTECTED_WITH_HAN : PROTECTED);
        }
        Collections.sort(literals, new Comparator<String>() {
            @Override
            public int compare(String first, String second) {
                return Integer.compare(second.length(), first.length());
            }
        });
        StringBuilder source = new StringBuilder("(?:(?<![A-Za-z0-9_])(?:");
        for (int index = 0; index < literals.size(); index++) {
            if (index > 0) {
                source.append('|');
            }
            source.append(Pattern.quote(literals.get(index)));
        }
        source.append(")(?![A-Za-z0-9_])|").append(PROTECTED_SOURCE);
        if (preserveHanText) {
            source.append('|').append(HAN_SOURCE);
        }
        source.append(')');
        return parseWithPattern(text, Pattern.compile(
                source.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    private static ProtectedText parseWithPattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer output = new StringBuffer();
        List<String> values = new ArrayList<String>();
        while (matcher.find()) {
            int index = values.size();
            values.add(matcher.group());
            matcher.appendReplacement(output, Matcher.quoteReplacement(token(index)));
        }
        matcher.appendTail(output);
        return new ProtectedText(text, output.toString(), values);
    }

    public String getOriginal() {
        return original;
    }

    public String getTemplate() {
        return template;
    }

    public List<String> getValues() {
        return values;
    }

    String getUnprotectedTemplateText() {
        return INTERNAL_TOKEN.matcher(template).replaceAll("");
    }

    /** Returns exact protected values and translatable text as separate ordered parts. */
    List<Segment> getSegments() {
        List<Segment> segments = new ArrayList<Segment>();
        Matcher matcher = INTERNAL_TOKEN.matcher(template);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                segments.add(new Segment(template.substring(cursor, matcher.start()), false));
            }
            int index = Integer.parseInt(
                    matcher.group().substring("__UT_".length(), matcher.group().length() - 2));
            segments.add(new Segment(values.get(index), true));
            cursor = matcher.end();
        }
        if (cursor < template.length()) {
            segments.add(new Segment(template.substring(cursor), false));
        }
        if (segments.isEmpty()) {
            segments.add(new Segment(original, false));
        }
        return Collections.unmodifiableList(segments);
    }

    public String restore(String translatedTemplate) {
        String restored = translatedTemplate;
        for (int i = 0; i < values.size(); i++) {
            restored = restored.replace(token(i), values.get(i));
        }
        return restored;
    }

    private static String token(int index) {
        return "__UT_" + index + "__";
    }

    static final class Segment {
        private final String text;
        private final boolean protectedValue;

        private Segment(String text, boolean protectedValue) {
            this.text = text;
            this.protectedValue = protectedValue;
        }

        String text() {
            return text;
        }

        boolean isProtectedValue() {
            return protectedValue;
        }
    }
}
