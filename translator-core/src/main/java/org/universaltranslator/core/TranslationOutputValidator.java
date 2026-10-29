package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects small-model failure modes before they reach the render cache. */
public final class TranslationOutputValidator {
    private static final Pattern TOKEN = Pattern.compile("__UT_\\d+__", Pattern.CASE_INSENSITIVE);
    /**
     * Phrasing earlier prompt versions used.
     *
     * <p>Kept on top of the live markers from {@link TranslationPrompt#echoMarkers()}: a reply that is
     * served from a cache written by an older build, or copied out of an old log, is still rejected.
     */
    private static final String[] HISTORICAL_INSTRUCTION_FRAGMENTS = {
            "translate minecraft server interface text",
            "minecraft server interface text from",
            "translate the minecraft ui text",
            "translate the user text to",
            "minecraft ui text in zh-cn",
            "return only the translation",
            "reply with only the translation",
            "output translated text only",
            "you are a translation engine",
            "preserve tokens like",
            "keep every __ut_",
            "copy every __ut_",
            "player names, numbers, urls",
            "minecraft formatting markers",
            "urls, punctuation and minecraft",
            "do not explain",
            "never repeat text",
            "游戏服务器界面文本从自动翻译",
            "服务器界面文本从自动翻译",
            "返回的是原文本翻译",
            "只返回译文",
            "仅返回翻译",
            "玩家名、数字、url",
            "minecraft格式标记",
            "不要解释",
            "翻译引擎"
    };
    /**
     * Every phrase a reply echoes when the model answers with its instructions: the wording the live
     * prompts use plus the historical phrasing above.
     */
    private static final String[] INSTRUCTION_FRAGMENTS = instructionFragments();
    /**
     * Every marker {@link #containsInstructionArtifact(String)} looks for, in one alternation.
     *
     * <p>The check used to copy the whole string to lower case and then run one {@code contains} per
     * fragment — 29 full scans and one equal-length copy for every segment, for every request, and
     * again on the render thread for every unseen string. One precompiled alternation folds the case
     * as it matches, so the scan happens once and nothing is copied.
     */
    private static final Pattern INSTRUCTION_ARTIFACT = Pattern.compile(
            artifactSource(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** The shortest fragment in the list; a shorter text cannot contain one. */
    private static final int MINIMUM_ARTIFACT_LENGTH = shortestFragment();

    private TranslationOutputValidator() {
    }

    /**
     * Validates translated text before it can be cached.
     *
     * <p>There is deliberately no length floor derived from {@code source}. CJK targets pack the
     * same meaning into far fewer characters, so a short translation of a short line is legitimate
     * and any source-relative floor rejects those systematically. Truncation is answered where it
     * originates: the providers judge completeness from the completion reason and the stream
     * terminator, which know what a finished answer looks like. The only floor here is absolute —
     * text that carries no letter or digit at all is not a translation.
     */
    public static String requireValid(String source, String translated) {
        if (source == null || translated == null || translated.trim().isEmpty()) {
            throw new IllegalArgumentException("Translation output is empty");
        }
        String output = unwrapQuotes(source, translated.trim());
        if (output.isEmpty()) {
            throw new IllegalArgumentException("Translation output is empty");
        }
        int maximumLength = Math.max(48, source.length() * 3 + 24);
        if (output.length() > maximumLength) {
            throw new IllegalArgumentException("Translation output is unexpectedly long");
        }
        requireContent(output);
        if (!source.contains("\n") && (output.contains("\n") || output.contains("\r"))) {
            throw new IllegalArgumentException("Translation output unexpectedly contains multiple lines");
        }
        if (containsInstructionArtifact(output)) {
            throw new IllegalArgumentException("Translation output echoed its instructions");
        }
        if (!tokenSequence(source).equals(tokenSequence(output))) {
            throw new IllegalArgumentException("Translation output changed or reordered protected tokens");
        }
        return output;
    }

    /**
     * Validates the restored text immediately before it can enter a render cache.
     *
     * <p>This overload deliberately performs no placeholder scan.
     * {@link ProtectedText#getSegments()} hands protected values back verbatim, so the restored
     * string reproduces their original text, and {@code __UT_0__} is a legal Minecraft player name
     * ({@code [A-Za-z0-9_]}, three to sixteen characters). Scanning the restored string therefore
     * rejects valid content while proving nothing: the segment path never puts a token into a
     * provider request, so a genuine leak has no route to happen in this architecture.
     *
     * <p>What does work is the token-sequence comparison in
     * {@link #requireValid(String, String)}: source and output must carry an identical token
     * sequence, which catches a placeholder that was changed, dropped or reordered on the way
     * through the provider.
     *
     * <p>Do not bring a {@code matcher.find()} scan back here. A caller that really does hand
     * tokens to a provider must use {@link #requireDisplaySafe(String, String, Set)}, which looks
     * only at the tokens that were actually sent.
     */
    public static String requireDisplaySafe(String source, String restored) {
        // Nothing was sent, so nothing can come back: the segment path never puts tokens into a
        // provider request, which is the same rule ProtectedText.restore() relies on.
        return requireDisplaySafe(source, restored, Collections.<String>emptySet());
    }

    /**
     * Validates restored text and looks for placeholders among {@code liveTokens} only.
     *
     * <p>This overload exists for a caller that hands tokens to a provider and therefore needs to
     * know whether one came back. No such caller exists today: the segment pipeline translates
     * unprotected segments and splices protected values back in afterwards, so it always passes an
     * empty set through the two-argument overload. It is kept because this is the shape the check
     * must take the moment a request does carry tokens, and deleting it would invite the unsafe
     * whole-string scan back into that path.
     *
     * <p>{@code liveTokens} must hold exactly the tokens sent with this request. Any other token in
     * the output is ordinary text, not a leak. An empty set switches the check off, which is the
     * correct answer for a request that carried none.
     *
     * @param liveTokens tokens handed to the provider for this request, never {@code null} in
     *                   practice but tolerated as {@code null} to mean "nothing was sent"
     */
    public static String requireDisplaySafe(
            String source, String restored, Set<String> liveTokens) {
        if (source == null || restored == null || restored.trim().isEmpty()) {
            throw new IllegalArgumentException("Restored translation output is empty");
        }
        // The checks below run on the trimmed view, but the text handed back is the restored one:
        // the segment path strips a segment before sending it and splices the original leading and
        // trailing whitespace back in, so trimming the whole answer here undid the indentation that
        // keeps scoreboards aligned and book pages laid out.
        String trimmed = restored.trim();
        int maximumLength = Math.max(48, source.length() * 3 + 24);
        if (trimmed.length() > maximumLength) {
            throw new IllegalArgumentException("Restored translation output is unexpectedly long");
        }
        if (!source.contains("\n") && (trimmed.contains("\n") || trimmed.contains("\r"))) {
            throw new IllegalArgumentException("Restored translation unexpectedly contains multiple lines");
        }
        if (liveTokens != null) {
            for (String token : liveTokens) {
                // Only a token that actually travelled to the provider can travel back out of it.
                if (token != null && !token.isEmpty() && restored.contains(token)) {
                    throw new IllegalArgumentException(
                            "Restored translation leaked an internal placeholder");
                }
            }
        }
        if (containsInstructionArtifact(trimmed)) {
            throw new IllegalArgumentException("Restored translation echoed its instructions");
        }
        return restored;
    }

    /** Prevents leaked model instructions/placeholders from being translated recursively. */
    public static boolean containsInternalArtifact(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return TOKEN.matcher(text).find() || containsInstructionArtifact(text);
    }

    /**
     * Rejects output that carries no content at all, the only length-independent floor available.
     *
     * <p>{@code trim()} stops at U+0020, so an answer made of ideographic spaces or no-break spaces
     * slips past the emptiness check in {@link #requireValid(String, String)}, and an answer that
     * reduced to punctuation ("...") is not a translation of anything either. Both are rejected
     * here and nowhere else: a floor scaled by source length would misfire on CJK targets, whose
     * translations are legitimately much shorter than the English source.
     */
    private static void requireContent(String output) {
        // Anything that is not whitespace, punctuation or an invisible format character is content,
        // so an emoji answer ("😂😂😂" for "lol") passes while "..." and "　　" do not.
        for (int index = 0; index < output.length(); index++) {
            int codePoint = output.codePointAt(index);
            index += Character.charCount(codePoint) - 1;
            if (!Character.isWhitespace(codePoint)
                    && !Character.isSpaceChar(codePoint)
                    && !isPunctuation(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT) {
                return;
            }
        }
        throw new IllegalArgumentException("Translation output is empty");
    }

    /**
     * Whether the code point belongs to one of the Unicode punctuation categories.
     *
     * <p>{@link Character} exposes {@code isLetter} / {@code isDigit} / {@code isSpaceChar} but no
     * {@code isPunctuation}, so the categories have to be listed. Symbols (including emoji) are
     * deliberately not punctuation: an answer made of emoji is content.
     */
    private static boolean isPunctuation(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
                return true;
            default:
                return false;
        }
    }

    private static boolean containsInstructionArtifact(String text) {
        if (text.length() < MINIMUM_ARTIFACT_LENGTH) {
            // Nothing this short can carry a fragment, so it does not pay for a scan at all.
            return false;
        }
        return INSTRUCTION_ARTIFACT.matcher(text).find();
    }

    /** The alternation source of {@link #INSTRUCTION_ARTIFACT}; every entry is quoted verbatim. */
    private static String artifactSource() {
        StringBuilder source = new StringBuilder();
        appendAlternative(source, "<|system|>");
        appendAlternative(source, "<|assistant|>");
        appendAlternative(source, "[quote]");
        for (int index = 0; index < INSTRUCTION_FRAGMENTS.length; index++) {
            appendAlternative(source, INSTRUCTION_FRAGMENTS[index]);
        }
        return source.toString();
    }

    /**
     * The live prompt wording followed by the historical phrasing.
     *
     * <p>The live half is {@link TranslationPrompt#echoMarkers()}, so a prompt edit changes what is
     * detected instead of leaving the detector on the previous wording.
     */
    private static String[] instructionFragments() {
        String[] live = TranslationPrompt.echoMarkers();
        List<String> fragments = new ArrayList<String>(live.length
                + HISTORICAL_INSTRUCTION_FRAGMENTS.length);
        for (int index = 0; index < live.length; index++) {
            fragments.add(live[index]);
        }
        for (int index = 0; index < HISTORICAL_INSTRUCTION_FRAGMENTS.length; index++) {
            fragments.add(HISTORICAL_INSTRUCTION_FRAGMENTS[index]);
        }
        return fragments.toArray(new String[fragments.size()]);
    }

    private static void appendAlternative(StringBuilder source, String fragment) {
        if (source.length() > 0) {
            source.append('|');
        }
        source.append(Pattern.quote(fragment));
    }

    private static int shortestFragment() {
        int shortest = Integer.MAX_VALUE;
        for (int index = 0; index < INSTRUCTION_FRAGMENTS.length; index++) {
            shortest = Math.min(shortest, INSTRUCTION_FRAGMENTS[index].length());
        }
        return shortest == Integer.MAX_VALUE ? 0 : shortest;
    }

    private static List<String> tokenSequence(String text) {
        List<String> tokens = new ArrayList<String>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * Strips the pair of quotes the model wrapped its answer in, and keeps a pair the source had.
     *
     * <p>A source line can be a quotation of its own ({@code He said "hi"} in a quest description), so
     * the model legitimately answers with a quoted translation. Stripping that pair removed quotes
     * the original text carried, and the stripped value is what got cached, so the missing quotes
     * stayed for good. Only a pair the source does not open with is dropped.
     */
    private static String unwrapQuotes(String source, String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if (isQuotePair(first, last) && !opensWithQuote(source, first)) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    private static boolean isQuotePair(char first, char last) {
        return (first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '\u201c' && last == '\u201d');
    }

    /** Whether the source text itself opens with this quote, so the pair is not the model's doing. */
    private static boolean opensWithQuote(String source, char quote) {
        if (source == null) {
            return false;
        }
        String trimmed = source.trim();
        return !trimmed.isEmpty() && trimmed.charAt(0) == quote;
    }
}
