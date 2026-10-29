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
     *
     * <p>The same applies to the configured value itself: once it passes {@link #MAX_CONFIG_LENGTH} the
     * rest of it has no effect, and that loss is reported through {@link Parsed#droppedForLength()}
     * instead of disappearing. The cut is made on a separator, because a value cut mid-entry leaves a
     * shorter keyword behind that matches <em>more</em> text than the player allowed.
     */
    public static Parsed parseWithReport(String configuredKeywords) {
        if (configuredKeywords == null || configuredKeywords.trim().isEmpty()) {
            return new Parsed(EMPTY, 0, 0, 0);
        }
        String bounded = configuredKeywords;
        int droppedForLength = 0;
        if (bounded.length() > MAX_CONFIG_LENGTH) {
            // Cut at a separator so the entry that straddles the limit is never turned into a shorter
            // keyword. Cutting mid-word used to make the pattern *broader* (a truncated "creditcard"
            // became "credit") and started blocking text the player had allowed through. Every
            // separator the split below accepts has to be recognised here too, or a value separated by
            // semicolons, line breaks or full-width punctuation was cut mid-entry anyway.
            int boundary = lastSeparatorBefore(bounded, MAX_CONFIG_LENGTH);
            int cut = boundary > 0 ? boundary : MAX_CONFIG_LENGTH;
            droppedForLength = countKeywords(bounded, cut);
            bounded = bounded.substring(0, cut);
        }
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
                // Dropped, not cut. The match is a substring test, so a keyword cut down to its first
                // MAX_KEYWORD_LENGTH characters matched every text that merely started with them --
                // broader than what the player configured, for a filter whose whole purpose is "this
                // text must not leave my machine".
                truncated++;
                continue;
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
            return new Parsed(EMPTY, dropped, truncated, droppedForLength);
        }
        return new Parsed(new TranslationBlocklist(Collections.unmodifiableList(
                new ArrayList<String>(unique))), dropped, truncated, droppedForLength);
    }

    /**
     * Counts the entries the length limit removed from {@code text} at or after {@code from}.
     *
     * <p>Scans characters rather than splitting, because the tail of a hand-edited value can be far
     * longer than the limit and there is no reason to allocate it.
     *
     * @param text configured value, whole
     * @param from index the kept part ends at
     * @return number of non-empty entries after {@code from}
     */
    private static int countKeywords(String text, int from) {
        int lost = 0;
        int index = from;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (isSeparator(character)) {
                index++;
                continue;
            }
            lost++;
            while (index < text.length() && !isSeparator(text.charAt(index))) {
                index++;
            }
        }
        return lost;
    }

    /**
     * Index of the last separator at or before {@code limit}, or -1 when there is none.
     *
     * <p>Shared by the length cut so that the boundary and the split below agree on what a separator
     * is; looking only for the ASCII comma made the cut land inside an entry for any value separated
     * by semicolons, line breaks or full-width punctuation.
     */
    private static int lastSeparatorBefore(String text, int limit) {
        int bounded = Math.min(limit, text.length() - 1);
        for (int index = bounded; index >= 0; index--) {
            if (isSeparator(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isSeparator(char character) {
        return character == ',' || character == ';' || character == '\r' || character == '\n'
                || character == '\uff0c' || character == '\uff1b';
    }

    /** One parse outcome plus the losses that the screen has to report. */
    public static final class Parsed {
        private final TranslationBlocklist blocklist;
        private final int dropped;
        private final int truncated;
        private final int droppedForLength;

        private Parsed(TranslationBlocklist blocklist, int dropped, int truncated, int droppedForLength) {
            this.blocklist = blocklist;
            this.dropped = dropped;
            this.truncated = truncated;
            this.droppedForLength = droppedForLength;
        }

        public TranslationBlocklist blocklist() {
            return blocklist;
        }

        /** Keywords ignored because {@link #MAX_KEYWORDS} was already reached. */
        public int dropped() {
            return dropped;
        }

        /**
         * Keywords discarded because they were longer than {@link #MAX_KEYWORD_LENGTH}.
         *
         * <p>They used to be cut down and used as their own prefix, which blocked more text than the
         * player configured; they are now ignored, and counted here so the screen can say so.
         */
        public int truncated() {
            return truncated;
        }

        /** Keywords removed because the configured value exceeded {@link #MAX_CONFIG_LENGTH}. */
        public int droppedForLength() {
            return droppedForLength;
        }

        /** Whether part of the configured list has no effect. */
        public boolean lossy() {
            return dropped > 0 || truncated > 0 || droppedForLength > 0;
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
