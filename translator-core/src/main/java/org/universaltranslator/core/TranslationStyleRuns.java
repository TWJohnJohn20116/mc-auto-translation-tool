package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plans per-run styling for translated Minecraft text components.
 *
 * <p>A render bridge that flattens a multi-sibling component into a single literal keeps only
 * the style of the root, so every server-provided colour except the first one is lost. This
 * class holds the Minecraft-independent half of the fix: it merges neighbouring runs that share
 * a style, decides whether a component must be rebuilt run by run instead of being flattened,
 * and picks the colour a translated run should use. Bridges stay responsible for reading
 * {@code Style} objects and for rebuilding the component tree.</p>
 *
 * <p>Minecraft types are deliberately absent. A bridge passes an opaque style key (the
 * {@code Style} instance itself, whose {@code equals} is a value comparison) plus the index of
 * the source sibling, so this module keeps compiling against every platform's own Minecraft
 * mappings.</p>
 */
public final class TranslationStyleRuns {
    private TranslationStyleRuns() {
    }

    /** One contiguous run of source text that shares a single style. */
    public static final class Run {
        private final String text;
        private final Object styleKey;
        private final int sourceIndex;

        Run(String text, Object styleKey, int sourceIndex) {
            this.text = text == null ? "" : text;
            this.styleKey = styleKey;
            this.sourceIndex = sourceIndex;
        }

        public String text() {
            return text;
        }

        /** The opaque key the caller supplied for this run's style. */
        public Object styleKey() {
            return styleKey;
        }

        /** Index of the first source sibling this run was merged from. */
        public int sourceIndex() {
            return sourceIndex;
        }
    }

    /**
     * Merges neighbouring runs that share a style, so a component tree built from many small
     * same-styled siblings still costs one translation per style change instead of one per
     * sibling. The merged run keeps the first contributing {@link Run#sourceIndex()}, which is
     * the style the caller should rebuild it with.
     *
     * @param texts     run texts in render order
     * @param styleKeys opaque style key per run, compared with {@link Object#equals(Object)}
     */
    public static List<Run> mergeAdjacent(List<String> texts, List<Object> styleKeys) {
        if (texts == null || styleKeys == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        int count = Math.min(texts.size(), styleKeys.size());
        List<Run> runs = new ArrayList<Run>(count);
        // Accumulate the text of the open run in a builder: a scoreboard side bar arrives as one
        // component with 40 same-styled siblings, and previous.text + text would copy the growing
        // prefix once per sibling, turning a per-frame merge into quadratic character copying.
        StringBuilder open = new StringBuilder();
        boolean hasOpen = false;
        Object openStyle = null;
        int openSourceIndex = -1;
        for (int index = 0; index < count; index++) {
            String text = texts.get(index) == null ? "" : texts.get(index);
            Object styleKey = styleKeys.get(index);
            if (hasOpen && equalKeys(openStyle, styleKey)) {
                open.append(text);
                continue;
            }
            if (hasOpen) {
                runs.add(new Run(open.toString(), openStyle, openSourceIndex));
                open.setLength(0);
            }
            open.append(text);
            openStyle = styleKey;
            openSourceIndex = index;
            hasOpen = true;
        }
        if (hasOpen) {
            runs.add(new Run(open.toString(), openStyle, openSourceIndex));
        }
        return runs;
    }

    /**
     * Number of distinct styles among the runs.
     *
     * <p>Diagnostic helper only. It is quadratic in the run count because the style keys are
     * opaque and compared by {@link Object#equals(Object)}, which for a Minecraft {@code Style}
     * compares every field. Do not call it on the render path; {@link #shouldRebuildRuns(List)}
     * does not need it.
     */
    public static int distinctStyleCount(List<Run> runs) {
        if (runs == null || runs.isEmpty()) {
            return 0;
        }
        List<Object> seen = new ArrayList<Object>(runs.size());
        for (Run run : runs) {
            boolean known = false;
            for (int index = 0; index < seen.size() && !known; index++) {
                known = equalKeys(seen.get(index), run.styleKey());
            }
            if (!known) {
                seen.add(run.styleKey());
            }
        }
        return seen.size();
    }

    /**
     * True when the caller must rebuild the component run by run: flattening it into a single
     * literal would keep only the first style. A single-style source stays on the ordinary
     * flattened path, where the whole line is translated as one unit.
     *
     * <p>{@link #mergeAdjacent} only merges neighbours that share a style, so two runs always
     * carry two different styles. More than one run is therefore enough: the counting scan this
     * used to run per frame was pure cost on the render thread.
     */
    public static boolean shouldRebuildRuns(List<Run> runs) {
        return runs != null && runs.size() > 1;
    }

    /**
     * True when the caller must rebuild the component run by run because the style its visible
     * text renders with is not the root's own style.
     *
     * <p>Besides the multi-style case, a single run still needs the rebuild whenever the style
     * that actually applies to the visible text differs from {@code rootStyleKey}: the colour,
     * bold, italic or click event then lives on a child component, and a literal flattened with
     * the root style alone silently drops it. Team prefixes and suffixes and most components a
     * server builds arrive exactly that way, so a scoreboard or tab-list line whose colour comes
     * from the team rather than from the root is drawn in the default colour without this.</p>
     *
     * @param runs         runs of the visible text, in render order
     * @param rootStyleKey opaque style key of the component the runs were collected from
     */
    public static boolean shouldRebuildRuns(List<Run> runs, Object rootStyleKey) {
        if (runs == null || runs.isEmpty()) {
            return false;
        }
        if (runs.size() > 1) {
            return true;
        }
        // Neighbouring runs always differ after mergeAdjacent, so one run means the whole visible
        // text renders with that single style: compare it with the root directly.
        return !equalKeys(runs.get(0).styleKey(), rootStyleKey);
    }

    /**
     * Colour a translated run should use. Mirrors
     * {@link TranslationTextStyling#applyTranslatedStyle}: a source that already carries a
     * colour keeps its own colours, and only an uncoloured source receives the configured
     * translation colour.
     */
    public static TranslationTextColor resolveTranslatedColor(
            boolean sourceHasColor,
            TranslationTextColor configured
    ) {
        if (sourceHasColor || configured == null || !configured.changesColor()) {
            return TranslationTextColor.ORIGINAL;
        }
        return configured;
    }

    private static boolean equalKeys(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }
}
