package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps recently typed client messages out of render-time translation. */
public final class RecentUserText {
    private static final int MAX_ENTRIES = 128;
    private static final long RETAIN_MILLIS = 30L * 60L * 1_000L;
    /**
     * How often the render path is allowed to rebuild the table without the entries that expired.
     *
     * <p>Nothing used to remove an expired entry except {@link #remember(String)} on a full table, so
     * one typed message kept the table non-empty for the rest of the session and the empty-table fast
     * path below never ran again -- every drawn line then paid for a normalization plus a hash lookup
     * for the whole session. Rebuilding is copy-on-write and only ever needed once a minute.
     */
    private static final long PRUNE_INTERVAL_MILLIS = 60_000L;

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
     * Every accepted form mapped to the millisecond it stops being relevant, plus the bare typed
     * texts.
     *
     * <p>A map rather than a list on purpose: the render path asks "was this exact rendered line
     * produced from something I typed?" once per drawn line per frame, so the answer has to be a
     * single lookup. Insertion order gives the eviction order.
     *
     * <p>The map holds both the bare typed text and the {@code prefix + text} forms, so the common
     * cases (exactly what was typed, or the separator the client itself added) are answered by one
     * hash lookup with no allocation at all.
     *
     * <p>The pair is published as one immutable holder, and rewritten copy-on-write by
     * {@link #remember(String)}. It used to be a mutable map behind {@code synchronized}, which the
     * render path entered two to four times per drawn line -- six to twelve thousand monitor
     * acquisitions per second once a player had typed anything in the last half hour, contending with
     * the thread that sent the message.
     */
    private static final class Retained {
        private static final Retained EMPTY = new Retained(
                Collections.<String, Long>emptyMap(), Collections.<String>emptyList(),
                Collections.<Integer, List<String>>emptyMap());

        /** Accepted forms, mapped to their expiry. */
        private final Map<String, Long> forms;
        /** The bare typed texts, so a suffix can be compared in place instead of by substring. */
        private final List<String> bodies;
        /**
         * {@link #bodies} bucketed by length. The suffix comparison can only ever match a body whose
         * length equals the length of the tail, and walking every body for it turned one hash lookup
         * into up to {@code MAX_SUFFIX_PROBES * MAX_ENTRIES} comparisons per drawn line per frame.
         */
        private final Map<Integer, List<String>> bodiesByLength;

        private Retained(Map<String, Long> forms, List<String> bodies,
                Map<Integer, List<String>> bodiesByLength) {
            this.forms = forms;
            this.bodies = bodies;
            this.bodiesByLength = bodiesByLength;
        }
    }

    private final AtomicReference<Retained> retained = new AtomicReference<Retained>(Retained.EMPTY);
    /** When {@link #pruneExpired(long)} may run again; see {@link #PRUNE_INTERVAL_MILLIS}. */
    private volatile long nextPruneAtMillis;

    public void remember(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + RETAIN_MILLIS;
        int maximumForms = MAX_ENTRIES * (PREFIXES.length + 1);
        Retained current = retained.get();
        Map<String, Long> forms = new LinkedHashMap<String, Long>(
                current.forms.size() + PREFIXES.length + 2);
        forms.putAll(current.forms);
        // Only a table that is already at its bound is swept. An expired entry already answers "no"
        // through its timestamp, so walking the whole table on every typed message bought nothing and
        // kept the lock the render path was waiting for.
        if (forms.size() > maximumForms) {
            removeExpired(forms, now);
        }
        forms.put(normalized, Long.valueOf(expiresAt));
        for (String prefix : PREFIXES) {
            forms.put(prefix + normalized, Long.valueOf(expiresAt));
        }
        while (forms.size() > maximumForms) {
            Iterator<String> oldest = forms.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        List<String> bodies = new ArrayList<String>(Math.min(current.bodies.size() + 1, MAX_ENTRIES));
        for (int index = 0; index < current.bodies.size(); index++) {
            String body = current.bodies.get(index);
            if (forms.containsKey(body)) {
                bodies.add(body);
            }
        }
        if (!bodies.contains(normalized)) {
            bodies.add(normalized);
        }
        while (bodies.size() > MAX_ENTRIES) {
            bodies.remove(0);
        }
        // The index is rebuilt from the bodies that survived the bound, so a lookup can never find a
        // body that is no longer part of the published table.
        retained.set(new Retained(forms, bodies, indexBodiesByLength(bodies)));
    }

    public boolean shouldPreserve(String renderedText) {
        long now = System.currentTimeMillis();
        if (now >= nextPruneAtMillis) {
            // Expired entries are dropped here too, not only by remember() on a full table: without
            // this the table stayed non-empty for the rest of the session after one typed message, so
            // the empty-table fast path below never applied again and every drawn line paid for the
            // normalization and the lookups.
            pruneExpired(now);
        }
        Retained snapshot = retained.get();
        // The tables are empty for almost every frame of a normal session, so it is tested before the
        // normalization allocates a copy of the rendered line.
        if (snapshot.forms.isEmpty()) {
            return false;
        }
        String rendered = normalize(renderedText);
        if (rendered.isEmpty()) {
            return false;
        }
        // Fast path: the exact line this class was told about (including the prefixed forms built
        // in remember) is one hash lookup.
        if (isRetained(snapshot, rendered, now)) {
            return true;
        }
        return matchesPrefixedForm(snapshot, rendered, now);
    }

    /**
     * Republishes the table without the entries that can no longer answer "yes".
     *
     * <p>Copy-on-write, so readers stay lock free; the compare-and-set publication means a message
     * typed while this ran is never forgotten, because the newer table it published simply wins and
     * the next round prunes again.
     *
     * @param now the time this call treats as the present
     */
    private void pruneExpired(long now) {
        nextPruneAtMillis = now + PRUNE_INTERVAL_MILLIS;
        Retained current = retained.get();
        if (current.forms.isEmpty()) {
            return;
        }
        Map<String, Long> forms = new LinkedHashMap<String, Long>(current.forms);
        removeExpired(forms, now);
        if (forms.isEmpty()) {
            retained.compareAndSet(current, Retained.EMPTY);
            return;
        }
        List<String> bodies = new ArrayList<String>(Math.min(current.bodies.size(), MAX_ENTRIES));
        for (int index = 0; index < current.bodies.size(); index++) {
            String body = current.bodies.get(index);
            if (forms.containsKey(body)) {
                bodies.add(body);
            }
        }
        retained.compareAndSet(current,
                new Retained(forms, bodies, indexBodiesByLength(bodies)));
    }

    public void clear() {
        retained.set(Retained.EMPTY);
        nextPruneAtMillis = 0L;
    }

    /**
     * Answers the "renderer inserted something in front of my text" cases: {@code <Player> hello
     * world}, {@code Player: hello world}, {@code [MVP] Player » hello world}, and so on.
     *
     * <p>This runs only after the exact lookup missed, and it starts with a prefilter that only
     * asks whether one of the separator markers appears anywhere in the line. Ordinary interface
     * text has none of them, so it returns false without touching the table and without allocating
     * a single string.
     */
    private boolean matchesPrefixedForm(Retained snapshot, String rendered, long now) {
        if (!containsAnyPrefixMarker(rendered)) {
            return false;
        }
        int probes = 0;
        for (String prefix : PREFIXES) {
            int at = rendered.lastIndexOf(prefix);
            while (at >= 0 && probes < MAX_SUFFIX_PROBES) {
                probes++;
                if (matchesTail(snapshot, rendered, at + prefix.length(), now)) {
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

    /**
     * Whether the tail of {@code rendered} starting at {@code from} is one of the retained texts.
     *
     * <p>Compared in place. Taking a substring of every drawn line allocated one string per line per
     * frame, and almost every chat line carries a separator ({@code <Steve> hello}, {@code Player:
     * hello}). The length test runs first, so at most one candidate reaches the character comparison.
     */
    private boolean matchesTail(Retained snapshot, String rendered, int from, long now) {
        int length = rendered.length() - from;
        if (length <= 0) {
            return false;
        }
        // Only a body of exactly this length can match, and the index keeps a handful of those at
        // most. Scanning every retained body instead made this loop grow with the stored messages on
        // every drawn line of every frame, on the render thread.
        List<String> candidates = snapshot.bodiesByLength.get(Integer.valueOf(length));
        if (candidates == null) {
            return false;
        }
        for (int index = 0; index < candidates.size(); index++) {
            String body = candidates.get(index);
            if (!rendered.regionMatches(from, body, 0, length)) {
                continue;
            }
            if (isRetained(snapshot, body, now)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRetained(Retained snapshot, String form, long now) {
        Long expiresAt = snapshot.forms.get(form);
        return expiresAt != null && expiresAt.longValue() > now;
    }

    private static void removeExpired(Map<String, Long> forms, long now) {
        Iterator<Map.Entry<String, Long>> iterator = forms.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().longValue() <= now) {
                iterator.remove();
            }
        }
    }

    /**
     * Buckets the bare typed texts by length, so a suffix comparison only has to look at the bodies
     * that can possibly match the tail it was given.
     *
     * <p>Built from the list that is published, never mutated afterwards: the index and the list are
     * two views of one immutable snapshot, so they cannot disagree.
     */
    private static Map<Integer, List<String>> indexBodiesByLength(List<String> bodies) {
        Map<Integer, List<String>> byLength =
                new LinkedHashMap<Integer, List<String>>(bodies.size() * 2 + 2);
        for (int index = 0; index < bodies.size(); index++) {
            String body = bodies.get(index);
            Integer length = Integer.valueOf(body.length());
            List<String> sameLength = byLength.get(length);
            if (sameLength == null) {
                sameLength = new ArrayList<String>(1);
                byLength.put(length, sameLength);
            }
            sameLength.add(body);
        }
        return byLength;
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

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return TranslationTextStyling.stripLegacyFormatting(text).trim();
    }
}
