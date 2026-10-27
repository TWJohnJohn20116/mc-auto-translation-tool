package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded, case-insensitive keyword list used to keep matching text out of translators.
 */
public final class TranslationBlocklist {
    public static final int MAX_CONFIG_LENGTH = 4_096;
    public static final int MAX_KEYWORDS = 100;
    public static final int MAX_KEYWORD_LENGTH = 128;

    private static final TranslationBlocklist EMPTY =
            new TranslationBlocklist(Collections.<String>emptyList());

    private final List<String> keywords;

    private TranslationBlocklist(List<String> keywords) {
        this.keywords = keywords;
    }

    public static TranslationBlocklist empty() {
        return EMPTY;
    }

    /** Accepts comma, semicolon, Chinese punctuation, or line-separated keywords. */
    public static TranslationBlocklist parse(String configuredKeywords) {
        return parseWithReport(configuredKeywords).blocklist();
    }

    /**
     * Parses the configured list and reports what had to be discarded.
     *
     * <p>{@link #parse(String)} used to truncate at {@link #MAX_KEYWORD_LENGTH} and stop at
     * {@link #MAX_KEYWORDS} without telling anyone, so the screen said "saved" while every keyword
     * after the hundredth was ignored and a long keyword was cut into something that matched nothing.
     * For a feature whose whole purpose is "this text must not leave my machine", a silent drop is the
     * wrong direction: the caller can now warn.
     */
    public static Parsed parseWithReport(String configuredKeywords) {
        if (configuredKeywords == null || configuredKeywords.trim().isEmpty()) {
            return new Parsed(EMPTY, 0, 0);
        }
        String bounded = configuredKeywords.length() > MAX_CONFIG_LENGTH
                ? configuredKeywords.substring(0, MAX_CONFIG_LENGTH)
                : configuredKeywords;
        String[] candidates = bounded.split("[,;\\r\\n\uff0c\uff1b]+");
        Set<String> unique = new LinkedHashSet<String>();
        int truncated = 0;
        int dropped = 0;
        for (String candidate : candidates) {
            String keyword = candidate.trim();
            if (keyword.isEmpty()) {
                continue;
            }
            if (keyword.length() > MAX_KEYWORD_LENGTH) {
                keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
                truncated++;
            }
            String folded = keyword.toLowerCase(Locale.ROOT);
            if (unique.size() >= MAX_KEYWORDS) {
                // The list is full, so this entry cannot take effect; count it instead of ignoring it.
                if (!unique.contains(folded)) {
                    dropped++;
                }
                continue;
            }
            unique.add(folded);
        }
        if (unique.isEmpty()) {
            return new Parsed(EMPTY, dropped, truncated);
        }
        return new Parsed(new TranslationBlocklist(Collections.unmodifiableList(
                new ArrayList<String>(unique))), dropped, truncated);
    }

    /** One parse outcome plus the losses that the screen has to report. */
    public static final class Parsed {
        private final TranslationBlocklist blocklist;
        private final int dropped;
        private final int truncated;

        private Parsed(TranslationBlocklist blocklist, int dropped, int truncated) {
            this.blocklist = blocklist;
            this.dropped = dropped;
            this.truncated = truncated;
        }

        public TranslationBlocklist blocklist() {
            return blocklist;
        }

        /** Keywords ignored because {@link #MAX_KEYWORDS} was already reached. */
        public int dropped() {
            return dropped;
        }

        /** Keywords cut short at {@link #MAX_KEYWORD_LENGTH}. */
        public int truncated() {
            return truncated;
        }

        /** Whether part of the configured list has no effect. */
        public boolean lossy() {
            return dropped > 0 || truncated > 0;
        }
    }

    public boolean matches(String text) {
        if (text == null || text.isEmpty() || keywords.isEmpty()) {
            return false;
        }
        String folded = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (folded.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return keywords.isEmpty();
    }

    public List<String> keywords() {
        return keywords;
    }
}
