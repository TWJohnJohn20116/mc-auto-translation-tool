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
        if (configuredKeywords == null || configuredKeywords.trim().isEmpty()) {
            return EMPTY;
        }
        String bounded = configuredKeywords.length() > MAX_CONFIG_LENGTH
                ? configuredKeywords.substring(0, MAX_CONFIG_LENGTH)
                : configuredKeywords;
        String[] candidates = bounded.split("[,;\\r\\n\uff0c\uff1b]+");
        Set<String> unique = new LinkedHashSet<String>();
        for (String candidate : candidates) {
            String keyword = candidate.trim();
            if (keyword.isEmpty()) {
                continue;
            }
            if (keyword.length() > MAX_KEYWORD_LENGTH) {
                keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
            }
            unique.add(keyword.toLowerCase(Locale.ROOT));
            if (unique.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        if (unique.isEmpty()) {
            return EMPTY;
        }
        return new TranslationBlocklist(Collections.unmodifiableList(
                new ArrayList<String>(unique)));
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
