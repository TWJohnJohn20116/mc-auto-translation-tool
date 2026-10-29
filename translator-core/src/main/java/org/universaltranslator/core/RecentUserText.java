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
     * Upper bound on how many marker occurrences the suffix comparison probes for one drawn line.
     * Chat lines carry at most a name and one separator; the cap only exists so that a pathological
     * line made of markers cannot turn the slow path into a quadratic walk.
     */
    private static final int MAX_SUFFIX_PROBES = 8;

    /**
     * Every accepted form mapped to the millisecond it stops being relevant.
     *
     * <p>A map rather than a list on purpose: the render path asks "was this exact rendered line
     * produced from something I typed?" once per drawn line per frame, so the answer has to be a
     * single lookup. Insertion order gives the eviction order.
     *
     * <p>The map holds both the bare typed text and the {@code prefix + text} forms, so the common
     * cases (exactly what was typed, or the separator the client itself added) are answered by one
     * hash lookup with no allocation at all.
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
        long now = System.currentTimeMillis();
        // Fast path: the exact line this class was told about (including the prefixed forms built
        // in remember) is one hash lookup.
        if (isRetained(rendered, now)) {
            return true;
        }
        return matchesPrefixedForm(rendered, now);
    }

    public synchronized void clear() {
        retained.clear();
    }

    /**
     * Answers the "renderer inserted something in front of my text" cases: {@code <Player> hello
     * world}, {@code Player: hello world}, {@code [MVP] Player » hello world}, and so on.
     *
     * <p>This runs only after the exact lookup missed, and it starts with a prefilter that only
     * asks whether one of the separator markers appears anywhere in the line. Ordinary interface
     * text has none of them, so it returns false without touching the map and without allocating
     * a single string; no walk over the retained forms is ever performed.
     */
    private boolean matchesPrefixedForm(String rendered, long now) {
        if (!containsAnyPrefixMarker(rendered)) {
            return false;
        }
        int probes = 0;
        for (String prefix : PREFIXES) {
            int at = rendered.lastIndexOf(prefix);
            while (at >= 0 && probes < MAX_SUFFIX_PROBES) {
                probes++;
                String body = rendered.substring(at + prefix.length());
                if (!body.isEmpty() && isRetained(body, now)) {
                    return true;
                }
                if (at == 0) {
                    break;
                }
                at = rendered.lastIndexOf(prefix, at - 1);
            }
            if (probes >= MAX_SUFFIX_PROBES) {
                break;
            }
        }
        return false;
    }

    /** Cheap prefilter shared by every drawn line: contained markers only, no allocation. */
    private static boolean containsAnyPrefixMarker(String rendered) {
        for (String prefix : PREFIXES) {
            if (rendered.indexOf(prefix) >= 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isRetained(String form, long now) {
        Long expiresAt = retained.get(form);
        return expiresAt != null && expiresAt.longValue() > now;
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
