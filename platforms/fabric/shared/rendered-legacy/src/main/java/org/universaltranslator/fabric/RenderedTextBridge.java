package org.universaltranslator.fabric;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationTextStyling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public Mixin injection target. Keep this signature stable across 1.8.9-1.12.2. */
public final class RenderedTextBridge {
    /**
     * The last line styled and the answer it produced.
     *
     * <p>{@code TranslationTextStyling.applyTranslatedStyle} scans the whole original line for a
     * legacy colour code and rebuilds the translated line into a fresh {@code String} once a
     * translated colour is configured. The same line is measured and then drawn, so without a memo
     * that work ran twice per line per frame; the single slot catches exactly that repeat.
     */
    private static String memoOriginal;
    private static String memoTranslated;
    private static TranslationTextColor memoColor;
    private static String memoStyled;

    private RenderedTextBridge() {
    }

    /**
     * Records that one injection of this generation really fired, once per session.
     *
     * <p>Every hook in this tree is declared {@code require = 0} so a descriptor that drifts between
     * Minecraft versions degrades one surface instead of failing the launch. That choice used to be
     * completely silent: nothing said which surfaces were alive, so "translation stopped working"
     * and "this version never hooked that entry point" looked identical.
     */
    public static void surface(String name, AtomicBoolean once) {
        if (once.compareAndSet(false, true)) {
            DebugLog.global().log("render-surface", "applied", name);
        }
    }

    public static String translate(String text) {
        if (TranslationRenderContext.isTextInput()) {
            return text;
        }
        if (text == null) {
            return null;
        }
        // The legacy draw and width hooks both arrive here for the same line of the same frame, so
        // the second call already has its answer: the lookup below is one set access, and the whole
        // guard chain plus the provider query stay off the render thread for every repeat.
        FrameMemo memo = FRAME_MEMO.get();
        String answered = memo.answer(text);
        if (answered != null) {
            return answered;
        }
        String result = translate(text, TranslationRenderContext.current());
        memo.remember(text, result);
        return result;
    }

    private static String translate(String text, TextKind kind) {
        if (text == null) {
            return null;
        }
        String translated = FabricTranslationRuntime.translateForRender(text, kind);
        if (text.equals(translated)) {
            return text;
        }
        return styledText(text, translated);
    }

    /**
     * How long one frame of answers stays usable.
     *
     * <p>A duration rather than a tick boundary: the per-tick hook lives in
     * {@code FabricClientBootstrapBase}, which does not know this bridge exists and is compiled with
     * three different bridges. Fifty milliseconds is longer than the gap between the draw of a line
     * and its measure and shorter than the interval at which a line still waiting for an answer has
     * to be asked again to look alive, so a stale entry can be at most about two frames old — the
     * same bound the modern {@code TranslationRenderContext} accepts for its own per-frame sets.
     */
    private static final long FRAME_MEMO_NANOS = 50L * 1_000_000L;
    /** Cap on {@link FrameMemo#answers}; one frame cannot legitimately need more. */
    private static final int MAX_FRAME_MEMO = 512;

    private static final ThreadLocal<FrameMemo> FRAME_MEMO = new ThreadLocal<FrameMemo>() {
        @Override
        protected FrameMemo initialValue() {
            return new FrameMemo();
        }
    };

    /**
     * Answers this thread already produced, by the string they were asked about.
     *
     * <p>The draw and width hooks both arrive here for the same line of the same frame, so one line
     * used to run the whole guard chain and one lookup two to four times. The sets that could have
     * answered this live in the {@code TranslationRenderContext} of two of the three legacy trees —
     * 1.13 pairs this bridge with a copy that has neither — and reaching them took four reflective
     * invocations per string, each allocating a varargs array, on the thread that renders. Owning
     * the memo here costs one set lookup and gives 1.13 the same reduction.
     *
     * <p>Keyed by the input, not by the output: the second hook is handed the line the first one
     * received, so remembering only what the bridge returned missed on every line that really was
     * translated — the lines that cost the most.
     */
    private static final class FrameMemo {
        private final Map<String, String> answers = new HashMap<String, String>();
        /**
         * What the bridge returned, so a hook handed a translation is answered with it instead of
         * translating the translation; that is the case the old output set covered.
         */
        private final Set<String> outputs = new HashSet<String>();
        private long startedAt;

        private String answer(String input) {
            long now = System.nanoTime();
            if (startedAt == 0L || now - startedAt > FRAME_MEMO_NANOS) {
                answers.clear();
                outputs.clear();
                startedAt = now;
                return null;
            }
            if (input != null && outputs.contains(input)) {
                return input;
            }
            return input == null ? null : answers.get(input);
        }

        private void remember(String input, String answer) {
            if (input == null) {
                return;
            }
            if (answers.size() >= MAX_FRAME_MEMO) {
                answers.clear();
                outputs.clear();
            }
            answers.put(input, answer);
            if (answer != null) {
                outputs.add(answer);
            }
        }
    }

    /** Styles one already translated line, reusing the answer when the same line is redrawn. */
    private static String styledText(String original, String translated) {
        TranslationTextColor color = FabricTranslationRuntime.translatedTextColor();
        String cached = memoStyled;
        if (cached != null && memoColor == color
                && (memoTranslated == translated
                || (memoTranslated != null && memoTranslated.equals(translated)))
                && (memoOriginal == original
                || (memoOriginal != null && memoOriginal.equals(original)))) {
            return cached;
        }
        String styled = TranslationTextStyling.applyTranslatedStyle(original, translated, color);
        memoOriginal = original;
        memoTranslated = translated;
        memoColor = color;
        memoStyled = styled;
        return styled;
    }

    public static List<String> translateTooltipLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<String> translatedLines = FabricTranslationRuntime.translateLinesForRender(
                lines, TextKind.TOOLTIP);
        List<String> replacement = null;
        // One colour lookup for the whole list, and one comparison per line: the previous body
        // asked for the colour inside the loop and compared the pair twice.
        TranslationTextColor color = FabricTranslationRuntime.translatedTextColor();
        for (int index = 0; index < lines.size(); index++) {
            String original = lines.get(index);
            String translated = translatedLines.get(index);
            if (original == null) {
                if (translated != null) {
                    if (replacement == null) {
                        replacement = new ArrayList<String>(lines);
                    }
                    replacement.set(index, translated);
                }
                continue;
            }
            if (original.equals(translated)) {
                continue;
            }
            // Styled through the helper rather than through the one-slot memo above: a tooltip is a
            // list of lines redrawn every frame while the cursor rests, so each of its lines would
            // evict the slot the chat and scoreboard lines need a moment later, and every tooltip
            // line is styled once anyway.
            String styled = TranslationTextStyling.applyTranslatedStyle(original, translated, color);
            if (!styled.equals(original)) {
                if (replacement == null) {
                    replacement = new ArrayList<String>(lines);
                }
                replacement.set(index, styled);
            }
        }
        return replacement == null ? lines : replacement;
    }
}
