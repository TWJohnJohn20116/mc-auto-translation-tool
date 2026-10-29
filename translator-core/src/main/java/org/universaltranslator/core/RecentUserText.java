package org.universaltranslator.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps recently typed client messages out of render-time translation. */
public final class RecentUserText {
    private static final int MAX_ENTRIES = 128;
    private static final long RETAIN_MILLIS = 30L * 60L * 1_000L;

    /**
     * Prefixes that chat renderers prepend to the message body. The prefixed
     * forms are built once in {@link #remember(String)} so that the render path
     * is a single hash lookup per line and allocates nothing.
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

    /**
     * Every accepted form mapped to the millisecond it stops being relevant.
     *
     * <p>A map rather than a list on purpose: the render path asks "was this exact rendered line
     * produced from something I typed?" once per drawn line per frame, so the answer has to be a
     * single lookup. Insertion order gives the eviction order.
     */
    private final Map<String, Long> retained = new LinkedHashMap<String, Long>();

    public synchronized void remember(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        removeExpired(now);
        long expiresAt = now + RETAIN_MILLIS;
        retained.put(normalized, Long.valueOf(expiresAt));
        for (String prefix : PREFIXES) {
            retained.put(prefix + normalized, Long.valueOf(expiresAt));
        }
        int maximumForms = MAX_ENTRIES * (PREFIXES.length + 1);
        while (retained.size() > maximumForms) {
            Iterator<String> oldest = retained.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    public synchronized boolean shouldPreserve(String renderedText) {
        // The map is empty for almost every frame of a normal session, so it is tested before the
        // normalization allocates a copy of the rendered line.
        if (retained.isEmpty()) {
            return false;
        }
        String rendered = normalize(renderedText);
        if (rendered.isEmpty()) {
            return false;
        }
        Long expiresAt = retained.get(rendered);
        return expiresAt != null && expiresAt.longValue() > System.currentTimeMillis();
    }

    public synchronized void clear() {
        retained.clear();
    }

    private void removeExpired(long now) {
        Iterator<Map.Entry<String, Long>> iterator = retained.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().longValue() <= now) {
                iterator.remove();
            }
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return TranslationTextStyling.stripLegacyFormatting(text).trim();
    }
}
