package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects small-model failure modes before they reach the render cache. */
public final class TranslationOutputValidator {
    /**
     * Matches the placeholders {@link ProtectedText} generates.
     *
     * <p>The grammar comes from {@link ProtectedText#TOKEN_SOURCE}, so a change there cannot leave
     * this detector behind; only the case folding is this side's business, because a model that
     * echoes a token in a different case still leaked it.
     */
    private static final Pattern TOKEN = Pattern.compile(
            ProtectedText.TOKEN_SOURCE, Pattern.CASE_INSENSITIVE);
    /** "只返回译文" rendered into an English target; also a plain imperative sentence. */
    private static final String RETURN_ONLY_TRANSLATION = "return only the translation";
    /** The live prompt's own sentence, lower-cased; also a plain imperative sentence. */
    private static final String REPLY_ONLY_TRANSLATION = "reply with only the translation";
    /** "只输出译文" rendered into an English target; also a plain imperative sentence. */
    private static final String OUTPUT_TRANSLATED_ONLY = "output translated text only";
    /**
     * Prompt sentences that are also ordinary English imperatives, so one sentence alone can be either
     * a restated instruction or the translation of the source line.
     *
     * <p>"只返回译文", "仅回复译文" and "只输出译文" are ordinary mod-UI strings, and these three are
     * exactly how a model renders them into an English target. Presence inside a longer answer is still
     * the echo {@link #containsInstructionArtifact(String)} exists for -- a restated prompt carries its
     * other phrasing too -- but an answer that is one of these sentences and nothing else is judged as
     * a translation, on the output side and on the source side alike. Judging presence alone rejected
     * that translation forever: a rejection is not the "cannot succeed" verdict, so the line bought the
     * same refusal every thirty seconds and never showed anything.
     */
    private static final String[] AMBIGUOUS_ECHO_PHRASES = {
            RETURN_ONLY_TRANSLATION,
            REPLY_ONLY_TRANSLATION,
            OUTPUT_TRANSLATED_ONLY
    };
    /**
     * Phrasing earlier prompt versions used.
     *
     * <p>Kept on top of the live markers from {@link TranslationPrompt#echoMarkers()}: a reply that is
     * served from a cache written by an older build, or copied out of an old log, is still rejected.
     *
     * <p>Only phrasing that cannot be a translation of anything belongs here: "do not explain",
     * "never repeat text" and "player names, numbers, urls" are also how a model legitimately renders
     * those sentences into an English target, and a rejection is the retry-every-thirty-seconds
     * failure, while a source carrying one of them was never sent to a provider at all because
     * {@link LanguageHeuristics} reads it as an internal artifact.
     *
     * <p>The three entries that are also plain English imperatives stay here for their presence test;
     * {@link #AMBIGUOUS_ECHO_PHRASES} carries the same constants so a whole-answer match can be judged
     * differently without spelling the sentences twice.
     */
    private static final String[] HISTORICAL_INSTRUCTION_FRAGMENTS = {
            "translate minecraft server interface text",
            "minecraft server interface text from",
            "translate the minecraft ui text",
            "translate the user text to",
            "minecraft ui text in zh-cn",
            RETURN_ONLY_TRANSLATION,
            REPLY_ONLY_TRANSLATION,
            OUTPUT_TRANSLATED_ONLY,
            "you are a translation engine",
            "preserve tokens like",
            "keep every __ut_",
            "copy every __ut_",
            "minecraft formatting markers",
            "urls, punctuation and minecraft",
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
     * Shortest historical phrasing that is still watched for, in characters.
     *
     * <p>A fragment shorter than this is far more likely to be a correct translation than an echoed
     * instruction: "翻译引擎" is exactly how a model renders {@code Translation Engine}, and rejecting
     * it made that line fail forever (a failure re-enters the retry loop every 30 seconds). The
     * entries below this length stay in the list as a record of what older prompts said; they are not
     * part of the detector.
     */
    private static final int MINIMUM_HISTORICAL_FRAGMENT_LENGTH = 12;
    /**
     * Markers that can never be part of a translation, whatever wording the prompt uses.
     *
     * <p>They are kept apart from the prompt phrasing because they are not prompt text, and because
     * {@link #shortestFragment()} has to see them: they are the shortest alternatives in the whole
     * alternation, so leaving them out of that measurement would let a text that can carry one skip
     * the scan entirely.
     */
    private static final String[] STRUCTURAL_MARKERS = {
            "<|system|>",
            "<|assistant|>",
            "[quote]"
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
    /**
     * Latin letters a source phrase needs before an unchanged answer counts as a provider failure.
     *
     * <p>Short replies are real answers ("OK", "GG", a single proper noun), so the equality check
     * would misfire on them, and every misfire sends the line into the retry loop.
     */
    private static final int MINIMUM_UNCHANGED_LATIN_LETTERS = 3;
    /**
     * How far a translation may grow past its source before it counts as garbage, in characters.
     *
     * <p>The ceiling exists to reject an endpoint that was pointed at the wrong URL and answered with
     * a page of HTML or JSON. The previous 3x + 24 was sized on Latin text and assumed source and
     * output use the same script, so a 20-character Chinese line legitimately answered with 95-115
     * characters of Russian was rejected -- and a rejection is not the "cannot succeed" verdict, so
     * the line retried every thirty seconds and never showed a translation at all.
     */
    private static final int MAXIMUM_OUTPUT_GROWTH = 6;
    /** Smallest ceiling {@link #maximumOutputLength(String)} allows, for very short sources. */
    private static final int MINIMUM_OUTPUT_CEILING = 96;
    /** Slack added past the growth allowance, so a short source keeps room for a whole sentence. */
    private static final int OUTPUT_CEILING_SLACK = 48;

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
        int maximumLength = maximumOutputLength(source);
        if (output.length() > maximumLength) {
            throw new IllegalArgumentException("Translation output is unexpectedly long");
        }
        requireContent(output);
        if (!source.contains("\n") && (output.contains("\n") || output.contains("\r"))) {
            throw new IllegalArgumentException("Translation output unexpectedly contains multiple lines");
        }
        // A reply that is the source text means the provider did nothing: the text was cached as its
        // own translation and repainted in the translated colour while the characters stayed the same.
        // Only a phrase is judged, because a single token is legitimately carried over verbatim (a
        // proper noun, an abbreviation) and every rejection sends the line into the retry loop.
        if (output.equals(source) && countLatinLetters(source) >= MINIMUM_UNCHANGED_LATIN_LETTERS
                && containsWhitespace(source)) {
            // Rejecting the text is right; the plain IllegalArgumentException was not, because it is
            // also what a fixable failure throws. See UnchangedOutputException.
            throw new UnchangedOutputException("Translation output repeats the source text");
        }
        // The prompt promises the same number of lines. Only a loss is rejected: a CJK target
        // legitimately wraps one long line into two, but nothing legitimate drops a line. Both sides
        // are counted after trimming, because the answer is trimmed above: counting the untrimmed
        // source made every text that ends with a line break -- what the render session builds from a
        // list whose last item is empty -- look one line shorter than it is, so it was rejected for
        // good and retried every thirty seconds.
        int sourceLines = lineCount(source.trim());
        if (sourceLines > 1 && lineCount(output) < sourceLines) {
            throw new IllegalArgumentException("Translation output dropped a line");
        }
        // A whole answer that is one of the ambiguous sentences is a translation, not an echo; a longer
        // answer that carries one is still rejected here.
        if (containsInstructionArtifact(output) && !isAmbiguousEcho(output)) {
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
        int maximumLength = maximumOutputLength(source);
        if (trimmed.length() > maximumLength) {
            throw new IllegalArgumentException("Restored translation output is unexpectedly long");
        }
        if (!source.contains("\n") && (trimmed.contains("\n") || trimmed.contains("\r"))) {
            throw new IllegalArgumentException("Restored translation unexpectedly contains multiple lines");
        }
        // Same loss rule as requireValid, with the same trimmed source: the restored text keeps one
        // line per source line, and this call is the one that sees the whole text a list was joined
        // into, so a leading or trailing line break would otherwise be counted as a lost line.
        int sourceLines = lineCount(source.trim());
        if (sourceLines > 1 && lineCount(trimmed) < sourceLines) {
            throw new IllegalArgumentException("Restored translation dropped a line");
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
        // Same whole-answer rule as requireValid: the restored text is judged by the lines it holds,
        // and an answer that is one of these sentences is the translation of such a line.
        if (containsInstructionArtifact(trimmed) && !isAmbiguousEcho(trimmed)) {
            throw new IllegalArgumentException("Restored translation echoed its instructions");
        }
        return restored;
    }

    /** Prevents leaked model instructions/placeholders from being translated recursively. */
    public static boolean containsInternalArtifact(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (TOKEN.matcher(text).find()) {
            return true;
        }
        // A server sign or plugin help line that reads exactly like one of these sentences is ordinary
        // text, and refusing to send it means the line is never translated at all -- silently, because
        // this check runs before a request exists.
        return containsInstructionArtifact(text) && !isAmbiguousEcho(text);
    }

    /**
     * Whether {@code text} is exactly one of {@link #AMBIGUOUS_ECHO_PHRASES} and nothing else.
     *
     * <p>Only the whole value is compared, so the sentences keep their presence test: a restated
     * prompt, or a sentence carrying a translation next to it, is still an echo. One closing mark is
     * allowed past the words themselves, because every prompt asks the model to preserve punctuation
     * and a translated sentence therefore arrives with the source's full stop: requiring the bare
     * sentence rejected that answer forever, and a rejection is the retry-every-thirty-seconds path.
     */
    private static boolean isAmbiguousEcho(String text) {
        String trimmed = text.trim();
        String bare = stripClosingPunctuation(trimmed);
        for (int index = 0; index < AMBIGUOUS_ECHO_PHRASES.length; index++) {
            if (AMBIGUOUS_ECHO_PHRASES[index].equalsIgnoreCase(trimmed)
                    || AMBIGUOUS_ECHO_PHRASES[index].equalsIgnoreCase(bare)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes one closing sentence mark, so "Return only the translation." is read as the sentence.
     *
     * <p>Exactly one, and only the marks a sentence ends with: a question or a longer answer is a
     * different text, and stripping repeatedly would let an echo hide behind a trail of punctuation.
     */
    private static String stripClosingPunctuation(String text) {
        if (text.isEmpty()) {
            return text;
        }
        char last = text.charAt(text.length() - 1);
        if (last == '.' || last == '\u3002' || last == '\uFF0E'
                || last == '!' || last == '\uFF01' || last == '?' || last == '\uFF1F') {
            return text.substring(0, text.length() - 1).trim();
        }
        return text;
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

    /**
     * Number of Latin letters in {@code text}, counted by code point.
     *
     * <p>Only Latin letters count towards the unchanged-answer rule: an identical Han answer is a
     * legitimate translation of a Han source (a Japanese target leaves "日本語" alone), while an
     * identical answer to a Latin source is a provider that did not translate.
     */
    private static int countLatinLetters(String text) {
        int letters = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isLetter(codePoint) && codePoint < 0x0250) {
                letters++;
            }
        }
        return letters;
    }

    /**
     * Whether {@code text} carries a word break, which is what makes it a phrase and not a token.
     */
    private static boolean containsWhitespace(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == ' ' || character == '\n' || character == '\r'
                    || character == '\u3000' || character == '\t') {
                return true;
            }
        }
        return false;
    }

    /**
     * Number of lines in {@code text}; every line break counts once, so {@code \r\n} is one break.
     *
     * <p>A hand-written loop, not {@code split}: this runs per segment and must not allocate an array,
     * and an empty source legitimately has one line.
     */
    private static int lineCount(String text) {
        int lines = 1;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\n') {
                lines++;
            } else if (character == '\r'
                    && (index + 1 >= text.length() || text.charAt(index + 1) != '\n')) {
                lines++;
            }
        }
        return lines;
    }

    /**
     * The longest answer accepted for one source, shared by both validators.
     *
     * <p>One helper and not two copies of the formula: the two checks had the same arithmetic spelled
     * out twice, so adjusting one left the other behind. This is a garbage guard, not a completeness
     * check -- line count and the token sequence are what catch a truncated or merged answer.
     */
    private static int maximumOutputLength(String source) {
        return Math.max(MINIMUM_OUTPUT_CEILING,
                source.length() * MAXIMUM_OUTPUT_GROWTH + OUTPUT_CEILING_SLACK);
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
        for (int index = 0; index < STRUCTURAL_MARKERS.length; index++) {
            appendAlternative(source, STRUCTURAL_MARKERS[index]);
        }
        for (int index = 0; index < INSTRUCTION_FRAGMENTS.length; index++) {
            appendAlternative(source, INSTRUCTION_FRAGMENTS[index]);
        }
        return source.toString();
    }

    /**
     * The live prompt wording followed by the historical phrasing that is specific enough to keep.
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
            String fragment = HISTORICAL_INSTRUCTION_FRAGMENTS[index];
            if (fragment.length() >= MINIMUM_HISTORICAL_FRAGMENT_LENGTH) {
                fragments.add(fragment);
            }
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
        for (int index = 0; index < STRUCTURAL_MARKERS.length; index++) {
            shortest = Math.min(shortest, STRUCTURAL_MARKERS[index].length());
        }
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

    /**
     * A provider answered with the source text, so this line is known not to be translatable by it.
     *
     * <p>{@link IllegalArgumentException} already tells the failure policy that the request cannot
     * succeed here, but the render session could not tell this verdict from a fixable failure: it put
     * the line back into the retry table every thirty seconds and bought the same unchanged answer for
     * the rest of the session, where the "nothing translatable" path remembers the verdict once and
     * asks never again. The subtype is the difference the session needs.
     */
    static final class UnchangedOutputException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        UnchangedOutputException(String message) {
            super(message);
        }
    }
}
