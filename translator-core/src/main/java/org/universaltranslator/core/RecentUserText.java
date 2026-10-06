package org.universaltranslator.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** Keeps recently typed client messages out of render-time translation. */
public final class RecentUserText {
    private static final int MAX_ENTRIES = 128;
    private static final long RETAIN_MILLIS = 30L * 60L * 1_000L;

    /**
     * Prefixes that chat renderers prepend to the message body. The prefixed
     * forms are built once in {@link #remember(String)} so that the render path
     * only compares against precomputed strings and allocates nothing.
     */
    private static final String[] PREFIXES = {
            "> ",
            ": ",
            "：",
            " » ",
            " › ",
            " >> ",
            " -> ",
    };

    private final Deque<Entry> entries = new ArrayDeque<Entry>();

    public synchronized void remember(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        removeExpired(now);
        entries.addFirst(new Entry(normalized, prefixedForms(normalized), now + RETAIN_MILLIS));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    public synchronized boolean shouldPreserve(String renderedText) {
        String rendered = normalize(renderedText);
        if (rendered.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        removeExpired(now);
        for (Entry entry : entries) {
            if (rendered.equals(entry.text) || entry.hasPrefixFormOf(rendered)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        entries.clear();
    }

    private void removeExpired(long now) {
        while (!entries.isEmpty() && entries.peekLast().expiresAt <= now) {
            entries.removeLast();
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return TranslationTextStyling.stripLegacyFormatting(text).trim();
    }

    /**
     * Builds every "{@code prefix + text}" form once, so that lookups on the
     * render path never concatenate. The plain text is not part of this array:
     * an exact match stays an equality check and must not become a suffix test.
     */
    private static String[] prefixedForms(String text) {
        String[] forms = new String[PREFIXES.length];
        for (int index = 0; index < PREFIXES.length; index++) {
            forms[index] = PREFIXES[index] + text;
        }
        return forms;
    }

    private static final class Entry {
        private final String text;
        private final String[] prefixedForms;
        private final long expiresAt;

        private Entry(String text, String[] prefixedForms, long expiresAt) {
            this.text = text;
            this.prefixedForms = prefixedForms;
            this.expiresAt = expiresAt;
        }

        /** True when the rendered line ends with one of the precomputed forms. */
        private boolean hasPrefixFormOf(String rendered) {
            for (String form : prefixedForms) {
                if (rendered.endsWith(form)) {
                    return true;
                }
            }
            return false;
        }
    }
}
