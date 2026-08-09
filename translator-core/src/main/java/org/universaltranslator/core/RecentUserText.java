package org.universaltranslator.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** Keeps recently typed client messages out of render-time translation. */
public final class RecentUserText {
    private static final int MAX_ENTRIES = 128;
    private static final long RETAIN_MILLIS = 30L * 60L * 1_000L;

    private final Deque<Entry> entries = new ArrayDeque<Entry>();

    public synchronized void remember(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        removeExpired(now);
        entries.addFirst(new Entry(normalized, now + RETAIN_MILLIS));
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
            if (rendered.equals(entry.text)
                    || rendered.endsWith("> " + entry.text)
                    || rendered.endsWith(": " + entry.text)
                    || rendered.endsWith("：" + entry.text)
                    || rendered.endsWith(" » " + entry.text)
                    || rendered.endsWith(" › " + entry.text)
                    || rendered.endsWith(" >> " + entry.text)
                    || rendered.endsWith(" -> " + entry.text)) {
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

    private static final class Entry {
        private final String text;
        private final long expiresAt;

        private Entry(String text, long expiresAt) {
            this.text = text;
            this.expiresAt = expiresAt;
        }
    }
}
