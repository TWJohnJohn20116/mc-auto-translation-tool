package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Replaces values that should survive translation verbatim with stable ASCII tokens.
 * This is especially important for rapidly changing scoreboards.
 */
public final class ProtectedText {
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)";
    /**
     * A Minecraft legacy formatting code: {@code §} followed by a hex colour or style character.
     * The code character is itself a letter or digit, so a value that directly follows a code
     * (for example {@code "§aplay.example.cn"}) would look glued to a word and every
     * word-boundary lookbehind below would reject it.
     */
    private static final String FORMAT_CODE = "\\u00a7[0-9A-FK-ORa-fk-or]";
    /** Fails when the position is preceded by a formatting code. */
    private static final String NOT_AFTER_FORMAT_CODE = "(?<!" + FORMAT_CODE + ")";
    private static final String NOT_AFTER_WORD = "(?<![A-Za-z0-9_.-])";
    private static final String NOT_AFTER_IDENTIFIER = "(?<![A-Za-z0-9_])";
    private static final String IPV4_CORE =
            IPV4_OCTET + "(?:\\." + IPV4_OCTET + "){3}(?::\\d{1,5})?(?![A-Za-z0-9_.-])";
    private static final String IPV4_SOURCE = NOT_AFTER_FORMAT_CODE + NOT_AFTER_WORD + IPV4_CORE;
    private static final String BRACKETED_IPV6_SOURCE =
            "\\[(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}\\](?::\\d{1,5})?";
    private static final String RAW_IPV6_CORE =
            "(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[A-Za-z0-9_.-]+)?(?![A-Za-z0-9_])";
    private static final String RAW_IPV6_SOURCE =
            NOT_AFTER_FORMAT_CODE + NOT_AFTER_IDENTIFIER + RAW_IPV6_CORE;
    private static final String DOMAIN_LABEL =
            "(?:_?[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)";
    private static final String DOMAIN_CORE =
            "(?:" + DOMAIN_LABEL + "\\.)+[A-Za-z]{2,63}(?::\\d{1,5})?(?![A-Za-z0-9_.-])";
    private static final String DOMAIN_SOURCE = NOT_AFTER_FORMAT_CODE + NOT_AFTER_WORD + DOMAIN_CORE;
    private static final String LOCALHOST_CORE = "localhost(?::\\d{1,5})?(?![A-Za-z0-9_.-])";
    private static final String LOCALHOST_SOURCE =
            NOT_AFTER_FORMAT_CODE + NOT_AFTER_WORD + LOCALHOST_CORE;
    private static final String NUMBER_CORE =
            "(?:\\d{1,3}(?:[.,]\\d{3})+|\\d+(?:[.,]\\d+)?)(?:%|ms|s|m|h|d)?(?![A-Za-z0-9_])";
    private static final String NUMBER_SOURCE =
            NOT_AFTER_FORMAT_CODE + NOT_AFTER_IDENTIFIER + NUMBER_CORE;
    /**
     * A formatting code directly in front of a protected value is kept inside the match. The value
     * then starts at a real boundary instead of hiding behind the code character, which is what
     * lets {@code "§aplay.example.cn"} or {@code "§e1000 coins"} stay protected.
     */
    private static final String FORMATTED_VALUE_SOURCE =
            FORMAT_CODE + "(?:" + BRACKETED_IPV6_SOURCE + "|" + IPV4_CORE + "|" + RAW_IPV6_CORE
                    + "|" + DOMAIN_CORE + "|" + LOCALHOST_CORE + "|" + NUMBER_CORE + ")";
    /**
     * A leading list bullet ("• ", "» ", "◆ ") is decoration rather than content. A model that
     * sees one inside a line replaces it with a different glyph of its own choosing, and because
     * the render bridge re-attaches styling per text run, that replacement also shifts the colours
     * of the line. Bullets are therefore kept verbatim and never sent for translation.
     */
    private static final String BULLET_CORE = "^[\\p{So}\\p{Sk}]+[ \\u3000]*";
    private static final String PROTECTED_SOURCE =
            "(?:" + FORMATTED_VALUE_SOURCE + ")" +
            "|(?:" + FORMAT_CODE + ")" +
            "|(?:https?://\\S+|www\\.\\S+)" +
            "|(?:" + BRACKETED_IPV6_SOURCE + "|" + IPV4_SOURCE + "|" + RAW_IPV6_SOURCE
                    + "|" + DOMAIN_SOURCE + "|" + LOCALHOST_SOURCE + ")" +
            "|(?:" + NUMBER_SOURCE + ")" +
            "|(?:" + BULLET_CORE + ")" +
            "|(?:%[A-Za-z0-9_.:-]+%)" +
            "|(?:\\{[A-Za-z0-9_.:-]+})";
    private static final String HAN_SOURCE =
            "(?:[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]+)";
    // MULTILINE so the leading-bullet pattern anchors at the start of every line, not just the
    // start of the whole text; none of the other patterns use an anchor.
    private static final Pattern PROTECTED = Pattern.compile(PROTECTED_SOURCE, Pattern.MULTILINE);
    private static final Pattern PROTECTED_WITH_HAN =
            Pattern.compile("(?:" + PROTECTED_SOURCE + "|" + HAN_SOURCE + ")", Pattern.MULTILINE);
    private static final Pattern INTERNAL_TOKEN = Pattern.compile("__UT_\\d+__");
    /**
     * How many distinct literal sets keep their compiled pattern alive. The platform publishes a
     * fresh player-name snapshot every few seconds, so a handful of slots is enough to serve every
     * worker between two snapshots while keeping the cache bounded.
     */
    private static final int PATTERN_CACHE_CAPACITY = 4;
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    /** Guards {@link #PATTERN_CACHE} and {@link #patternCacheCursor}. */
    private static final Object PATTERN_CACHE_LOCK = new Object();
    private static final PatternCacheEntry[] PATTERN_CACHE =
            new PatternCacheEntry[PATTERN_CACHE_CAPACITY];
    private static int patternCacheCursor;

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
        Set<String> seenLiterals = new HashSet<String>();
        if (protectedLiterals != null) {
            for (String literal : protectedLiterals) {
                if (literal != null && !literal.isEmpty() && literal.length() <= 255
                        && literals.size() < 1000 && seenLiterals.add(literal)) {
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
        return parseWithPattern(text, cachedPattern(literals, preserveHanText));
    }

    /**
     * Builds the alternation source and compiles it. This is the expensive step, so callers go
     * through {@link #cachedPattern(List, boolean)} instead of calling it directly.
     */
    private static Pattern compilePattern(List<String> literals, boolean preserveHanText) {
        StringBuilder source = new StringBuilder(
                "(?:" + NOT_AFTER_FORMAT_CODE + "(?<![A-Za-z0-9_])(?:" + FORMAT_CODE + ")*(?:");
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
        return Pattern.compile(
                source.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Returns the compiled pattern for an already deduplicated and sorted literal list, reusing a
     * previously compiled one when the same content has just been seen.
     *
     * <p>The key is the literal <em>content</em>, never the iterable identity: the platform
     * republishes an equal-but-new {@code List} every few seconds, so an identity key would never
     * hit. {@link #signature(List, boolean)} is only a fast reject filter; the entry is reused
     * solely when {@link List#equals(Object)} confirms that every literal matches, so a signature
     * collision can never hand back a pattern built from different literals.
     */
    private static Pattern cachedPattern(List<String> literals, boolean preserveHanText) {
        long signature = signature(literals, preserveHanText);
        synchronized (PATTERN_CACHE_LOCK) {
            for (int index = 0; index < PATTERN_CACHE.length; index++) {
                PatternCacheEntry entry = PATTERN_CACHE[index];
                if (entry != null && entry.matches(signature, preserveHanText, literals)) {
                    return entry.pattern;
                }
            }
            // Compiling under the lock keeps a burst of workers that all miss on a freshly
            // published snapshot down to a single compilation instead of one per thread.
            Pattern compiled = compilePattern(literals, preserveHanText);
            PATTERN_CACHE[patternCacheCursor] =
                    new PatternCacheEntry(signature, preserveHanText, literals, compiled);
            patternCacheCursor = (patternCacheCursor + 1) % PATTERN_CACHE.length;
            return compiled;
        }
    }

    /**
     * FNV-1a over the length and characters of every literal, with a separator between entries and
     * the Han flag mixed in first. Framing each literal with its length keeps {@code ["ab", "c"]}
     * apart from {@code ["a", "bc"]}, so equal-length-but-different content never shares a key.
     */
    private static long signature(List<String> literals, boolean preserveHanText) {
        long hash = FNV_OFFSET_BASIS;
        hash = (hash ^ (preserveHanText ? 1L : 0L)) * FNV_PRIME;
        for (int index = 0; index < literals.size(); index++) {
            String literal = literals.get(index);
            int length = literal.length();
            hash = (hash ^ length) * FNV_PRIME;
            hash = (hash ^ 0x1fL) * FNV_PRIME;
            for (int offset = 0; offset < length; offset++) {
                hash = (hash ^ literal.charAt(offset)) * FNV_PRIME;
            }
        }
        return hash;
    }

    private static final class PatternCacheEntry {
        private final long signature;
        private final boolean preserveHanText;
        private final List<String> literals;
        private final Pattern pattern;

        private PatternCacheEntry(
                long signature,
                boolean preserveHanText,
                List<String> literals,
                Pattern pattern
        ) {
            this.signature = signature;
            this.preserveHanText = preserveHanText;
            this.literals = Collections.unmodifiableList(new ArrayList<String>(literals));
            this.pattern = pattern;
        }

        private boolean matches(
                long candidateSignature,
                boolean candidateHan,
                List<String> candidate
        ) {
            return signature == candidateSignature
                    && preserveHanText == candidateHan
                    && literals.equals(candidate);
        }
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
            int index = tokenIndex(matcher.group());
            if (index >= 0 && index < values.size()) {
                segments.add(new Segment(values.get(index), true));
            } else {
                segments.add(new Segment(matcher.group(), false));
            }
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
        // Single pass: copy template text verbatim and splice in the original values, so a value
        // that happens to contain a token literal (e.g. "__UT_1__") is never re-scanned.
        Matcher matcher = INTERNAL_TOKEN.matcher(translatedTemplate);
        StringBuilder output = new StringBuilder(translatedTemplate.length());
        int cursor = 0;
        while (matcher.find()) {
            output.append(translatedTemplate, cursor, matcher.start());
            int index = tokenIndex(matcher.group());
            if (index >= 0 && index < values.size()) {
                output.append(values.get(index));
            } else {
                output.append(matcher.group());
            }
            cursor = matcher.end();
        }
        output.append(translatedTemplate, cursor, translatedTemplate.length());
        return output.toString();
    }

    private static String token(int index) {
        return "__UT_" + index + "__";
    }

    /**
     * Extracts the index from an internal token. Returns {@code -1} for indexes that do not fit in
     * an {@code int}, so an unexpected token is treated as literal text instead of failing.
     */
    private static int tokenIndex(String token) {
        try {
            return Integer.parseInt(token.substring("__UT_".length(), token.length() - 2));
        } catch (NumberFormatException overflow) {
            return -1;
        }
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
