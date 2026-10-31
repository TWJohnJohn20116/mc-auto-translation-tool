package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Replaces values that should survive translation verbatim with stable ASCII tokens.
 * This is especially important for rapidly changing scoreboards.
 */
public final class ProtectedText {
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)";
    /**
     * A Minecraft legacy formatting code: {@code §} followed by a hex colour or style character.
     * The code character is itself a letter or digit, so a value that directly follows a code
     * (for example {@code "§aplay.example.cn"}) would look glued to a word and every
     * word-boundary lookbehind below would reject it.
     */
    private static final String FORMAT_CODE = "\\u00a7[0-9A-FK-ORa-fk-or]";
    /** Fails when the position is preceded by a formatting code. */
    private static final String NOT_AFTER_FORMAT_CODE = "(?<!" + FORMAT_CODE + ")";
    private static final String NOT_AFTER_WORD = "(?<![A-Za-z0-9_.-])";
    private static final String NOT_AFTER_IDENTIFIER = "(?<![A-Za-z0-9_])";
    private static final String IPV4_CORE =
            IPV4_OCTET + "(?:\\." + IPV4_OCTET + "){3}(?::\\d{1,5})?(?![A-Za-z0-9_.-])";
    private static final String IPV4_SOURCE = NOT_AFTER_FORMAT_CODE + NOT_AFTER_WORD + IPV4_CORE;
    private static final String BRACKETED_IPV6_SOURCE =
            "\\[(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}\\](?::\\d{1,5})?";
    private static final String RAW_IPV6_CORE =
            "(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[A-Za-z0-9_.-]+)?(?![A-Za-z0-9_])";
    private static final String RAW_IPV6_SOURCE =
            NOT_AFTER_FORMAT_CODE + NOT_AFTER_IDENTIFIER + RAW_IPV6_CORE;
    private static final String DOMAIN_LABEL =
            "(?:_?[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)";
    private static final String DOMAIN_CORE =
            "(?:" + DOMAIN_LABEL + "\\.)+[A-Za-z]{2,63}(?::\\d{1,5})?(?![A-Za-z0-9_.-])";
    private static final String DOMAIN_SOURCE = NOT_AFTER_FORMAT_CODE + NOT_AFTER_WORD + DOMAIN_CORE;
    private static final String LOCALHOST_CORE = "localhost(?::\\d{1,5})?(?![A-Za-z0-9_.-])";
    private static final String LOCALHOST_SOURCE =
            NOT_AFTER_FORMAT_CODE + NOT_AFTER_WORD + LOCALHOST_CORE;
    private static final String NUMBER_CORE =
            "(?:\\d{1,3}(?:[.,]\\d{3})+|\\d+(?:[.,]\\d+)?)(?:%|ms|s|m|h|d)?(?![A-Za-z0-9_])";
    private static final String NUMBER_SOURCE =
            NOT_AFTER_FORMAT_CODE + NOT_AFTER_IDENTIFIER + NUMBER_CORE;
    /**
     * A formatting code directly in front of a protected value is kept inside the match. The value
     * then starts at a real boundary instead of hiding behind the code character, which is what
     * lets {@code "§aplay.example.cn"} or {@code "§e1000 coins"} stay protected.
     */
    private static final String FORMATTED_VALUE_SOURCE =
            FORMAT_CODE + "(?:" + BRACKETED_IPV6_SOURCE + "|" + IPV4_CORE + "|" + RAW_IPV6_CORE
                    + "|" + DOMAIN_CORE + "|" + LOCALHOST_CORE + "|" + NUMBER_CORE + ")";
    /**
     * A leading list bullet ("• ", "» ", "◆ ") is decoration rather than content. A model that
     * sees one inside a line replaces it with a different glyph of its own choosing, and because
     * the render bridge re-attaches styling per text run, that replacement also shifts the colours
     * of the line. Bullets are therefore kept verbatim and never sent for translation.
     *
     * <p>The class covers every Unicode symbol and punctuation category on purpose: the common
     * bullets are split across them ("•" U+2022 is punctuation, "◆" U+25C6 is a symbol, "»" U+00BB
     * is final punctuation), and a run of leading decoration is never translatable text.
     *
     * <p>The run also swallows the indentation and the formatting codes in front of it, together
     * with the spaces a tab list puts between a code and the symbol it decorates, and it only
     * ends at a space, a line break, the end of the text or a non-ASCII character. That anchoring is
     * what keeps the pattern from claiming a placeholder: {@code "§7%player%"} must stay two tokens
     * (the format code and the placeholder), not "§7%" plus "player%".
     */
    private static final String BULLET_CORE = "^[ \\u3000]*(?:" + FORMAT_CODE
            + "[ \\u3000]*)*[\\p{S}\\p{P}]+(?:[ \\u3000]+|(?=\\n)|$|(?=[^\\x00-\\x7F]))";
    // The bullet branch sits before the bare FORMAT_CODE branch on purpose. Java takes the first
    // alternative that matches at the leftmost position, so a formatting code in front of a bullet
    // was consumed on its own and the bullet behind it was never at the start of a line any more,
    // which is the shape every scoreboard and tab list uses ("§7◆ §f<name>").
    private static final String PROTECTED_SOURCE =
            "(?:" + FORMATTED_VALUE_SOURCE + ")" +
            "|(?:" + BULLET_CORE + ")" +
            "|(?:" + FORMAT_CODE + ")" +
            "|(?:https?://\\S+|www\\.\\S+)" +
            "|(?:" + BRACKETED_IPV6_SOURCE + "|" + IPV4_SOURCE + "|" + RAW_IPV6_SOURCE
                    + "|" + DOMAIN_SOURCE + "|" + LOCALHOST_SOURCE + ")" +
            "|(?:" + NUMBER_SOURCE + ")" +
            "|(?:%[A-Za-z0-9_.:-]+%)" +
            "|(?:\\{[A-Za-z0-9_.:-]+})";
    // The supplementary ranges are written as \x{...} code points: CJK Extension B (20000..2FA1F) and
    // Extension C..F (30000..323AF) do not fit in a char, and leaving them out meant a rare Han
    // character was not protected while preserveHanText was on, so it was sent to the provider and
    // could come back rewritten or replaced by a different glyph.
    private static final String HAN_SOURCE =
            "(?:[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF"
                    + "\\x{20000}-\\x{2FA1F}\\x{30000}-\\x{323AF}]+)";
    // MULTILINE so the leading-bullet pattern anchors at the start of every line, not just the
    // start of the whole text; none of the other patterns use an anchor.
    private static final Pattern PROTECTED = Pattern.compile(PROTECTED_SOURCE, Pattern.MULTILINE);
    private static final Pattern PROTECTED_WITH_HAN =
            Pattern.compile("(?:" + PROTECTED_SOURCE + "|" + HAN_SOURCE + ")", Pattern.MULTILINE);
    /** The prefix {@code appendToken} writes in front of the index. */
    static final String TOKEN_PREFIX = "__UT_";
    /** The suffix {@code appendToken} writes behind the index. */
    static final String TOKEN_SUFFIX = "__";
    /**
     * The token grammar, and the only copy of it.
     *
     * <p>The generator ({@code appendToken}), the parser ({@link #INTERNAL_TOKEN}) and the leak
     * detector ({@link TranslationOutputValidator#containsInternalArtifact(String)}) used to spell
     * this out three times, so changing one of them turned the detector into a silent no-op. The
     * validator still decides case folding on its own: it is a matcher, not the generator.
     */
    static final String TOKEN_SOURCE = TOKEN_PREFIX + "\\d+" + TOKEN_SUFFIX;
    private static final Pattern INTERNAL_TOKEN = Pattern.compile(TOKEN_SOURCE);
    /**
     * Sessions that can translate at the same time: a second game window (or the self test) may run
     * while a settings change retires the first one, and both keep publishing literal snapshots.
     */
    private static final int CONCURRENT_SESSIONS = 2;
    /** Literal sets one session holds: the one with and the one without Han protection. */
    private static final int LITERAL_SETS_PER_SESSION = 2;
    /**
     * How many published literal snapshots keep their prepared form. It is the session count times
     * the sets per session, so retiring one session cannot evict the set a live request still uses;
     * a single slot would be overwritten by the other session on every request.
     */
    private static final int PREPARED_CAPACITY = CONCURRENT_SESSIONS * LITERAL_SETS_PER_SESSION;
    /**
     * How many distinct literal sets keep their compiled pattern alive. The platform publishes a
     * fresh player-name snapshot every few seconds, so this many slots is enough to serve every
     * worker between two publications while keeping the cache bounded.
     */
    private static final int PATTERN_CACHE_CAPACITY = CONCURRENT_SESSIONS * LITERAL_SETS_PER_SESSION;
    /** Longest literal kept; a server MOTD or a long title is still an exact string to preserve. */
    private static final int MAXIMUM_LITERAL_LENGTH = 1024;
    /** Most literals kept from one snapshot; the alternation has to stay a bounded size. */
    private static final int MAXIMUM_LITERALS = 1000;
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    /** Longest literal first, so the alternation tries the longest form before any of its prefixes. */
    private static final Comparator<String> LITERAL_LENGTH_ORDER = new Comparator<String>() {
        @Override
        public int compare(String first, String second) {
            return Integer.compare(second.length(), first.length());
        }
    };
    /**
     * Guards {@link #patternCache} and {@link #patternCacheCursor}. Only insertion takes it: readers
     * never block, because the array is replaced as a whole instead of being written in place.
     */
    private static final Object PATTERN_CACHE_LOCK = new Object();
    /**
     * The compiled patterns, published as an immutable snapshot. Every insertion clones the array and
     * publishes the clone, so a reader either sees the old contents or the new contents and never a
     * half-written slot. The previous lock-everything version serialized every request of every
     * session on this one monitor, which is the wrong shape for a lookup that almost always hits.
     */
    private static volatile PatternCacheEntry[] patternCache =
            new PatternCacheEntry[PATTERN_CACHE_CAPACITY];
    /** Guarded by {@link #PATTERN_CACHE_LOCK}. */
    private static int patternCacheCursor;
    /** Guards {@link #preparedLiterals} and {@link #preparedCursor}. Insertion only. */
    private static final Object PREPARED_LOCK = new Object();
    /**
     * The recently prepared literal snapshots, replaced as a whole like {@link #patternCache} so that
     * the deduplication, the sort and the content signature run once per published snapshot instead
     * of once per request.
     */
    private static volatile PreparedLiterals[] preparedLiterals =
            new PreparedLiterals[PREPARED_CAPACITY];
    /** Guarded by {@link #PREPARED_LOCK}. */
    private static int preparedCursor;

    private final String original;
    private final String template;
    private final List<String> values;
    /** The single token scan of {@link #template}; see {@link #splice()}. */
    private volatile Splice splice;

    private ProtectedText(String original, String template, List<String> values) {
        this.original = original;
        this.template = template;
        this.values = Collections.unmodifiableList(values);
    }

    public static ProtectedText parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }

        return parseWithPattern(text, PROTECTED);
    }

    public static ProtectedText parse(String text, Iterable<String> protectedLiterals) {
        return parse(text, protectedLiterals, false);
    }

    /**
     * Parses protected values and optionally protects existing Han text. Protecting Han text
     * lets mixed Chinese/English labels translate only their English portion.
     */
    public static ProtectedText parse(
            String text,
            Iterable<String> protectedLiterals,
            boolean preserveHanText
    ) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        PreparedLiterals prepared = prepared(protectedLiterals, preserveHanText);
        if (prepared.literals.isEmpty()) {
            return parseWithPattern(text, preserveHanText ? PROTECTED_WITH_HAN : PROTECTED);
        }
        return parseWithPattern(text, cachedPattern(prepared));
    }

    /**
     * The deduplicated, length-sorted, content-signed form of one published literal snapshot.
     *
     * <p>Every provider request used to rebuild it from scratch: a fresh {@code ArrayList}, a fresh
     * {@code HashSet} for the duplicate check, a sort with a freshly allocated comparator, and a full
     * FNV pass over every character — for a list the platform had not changed since the previous
     * request. The snapshot is republished as a new immutable list only every few seconds, so it is
     * remembered by identity and the work happens once per publication instead of once per request.
     * Identity is safe in the same way it is for the coordinator's request key: the platform hands out
     * a snapshot that keeps its contents until it is replaced.
     *
     * <p><strong>The snapshot must not be mutated.</strong> Publishing a new list is the only
     * supported way to change the set: an in-place update keeps the same identity, so the remembered
     * form would still describe the old contents (new names unprotected, removed names protected).
     * The entry count is compared on every hit so the common in-place update — clearing the list and
     * refilling it — is caught and rebuilt instead of silently served stale.
     *
     * @param protectedLiterals published snapshot, possibly {@code null}
     * @param preserveHanText whether Han text is protected as well
     * @return the prepared form; {@code literals} is empty when nothing is protected
     */
    private static PreparedLiterals prepared(Iterable<String> protectedLiterals, boolean preserveHanText) {
        PreparedLiterals[] current = preparedLiterals;
        for (int index = 0; index < current.length; index++) {
            PreparedLiterals entry = current[index];
            if (entry != null && entry.matches(protectedLiterals, preserveHanText)) {
                return entry;
            }
        }
        PreparedLiterals built = new PreparedLiterals(
                protectedLiterals, preserveHanText, adaptLiterals(protectedLiterals));
        synchronized (PREPARED_LOCK) {
            current = preparedLiterals;
            PreparedLiterals[] next = current.clone();
            next[preparedCursor] = built;
            preparedCursor = (preparedCursor + 1) % next.length;
            preparedLiterals = next;
        }
        return built;
    }

    /**
     * Filters, deduplicates and orders the raw literals.
     *
     * <p>Both bounds are part of the contract rather than an implementation detail: an entry longer
     * than {@link #MAXIMUM_LITERAL_LENGTH} or one past {@link #MAXIMUM_LITERALS} is <em>not</em>
     * protected, so it is sent to the provider and may come back rewritten. The bounds are named and
     * sized so the platform can decide, instead of depending on numbers buried in the loop.
     */
    private static List<String> adaptLiterals(Iterable<String> protectedLiterals) {
        List<String> literals = new ArrayList<String>();
        Set<String> seenLiterals = new HashSet<String>();
        if (protectedLiterals != null) {
            for (String literal : protectedLiterals) {
                if (literal != null && !literal.isEmpty()
                        && literal.length() <= MAXIMUM_LITERAL_LENGTH
                        && literals.size() < MAXIMUM_LITERALS && seenLiterals.add(literal)) {
                    literals.add(literal);
                }
            }
        }
        if (!literals.isEmpty()) {
            Collections.sort(literals, LITERAL_LENGTH_ORDER);
        }
        return literals;
    }

    /**
     * Builds the alternation source and compiles it. This is the expensive step, so callers go
     * through {@link #cachedPattern(List, boolean)} instead of calling it directly.
     */
    private static Pattern compilePattern(List<String> literals, boolean preserveHanText) {
        StringBuilder source = new StringBuilder(
                "(?:" + NOT_AFTER_FORMAT_CODE + "(?<![A-Za-z0-9_])(?:" + FORMAT_CODE + ")*(?iu:");
        appendLiteralAlternation(source, literals);
        source.append(")(?![A-Za-z0-9_])|").append(PROTECTED_SOURCE);
        if (preserveHanText) {
            source.append('|').append(HAN_SOURCE);
        }
        source.append(')');
        // MULTILINE for the same reason as PROTECTED: an inlined bullet pattern anchors with '^',
        // and without MULTILINE that anchor only matches the start of the whole text, so every
        // bullet on the second and later lines of a scoreboard or lore block would be sent for
        // translation as soon as the literal list is non-empty (which is always true in multiplayer).
        //
        // Case folding is scoped to the literal branch. Applying it to the whole pattern also folded
        // the inlined PROTECTED_SOURCE, so a unit suffix ("100MS"), a scheme ("HTTPS://"), "localhost"
        // or a "%Token%" was protected on a server and translated in single player: the same text got
        // two different segmentations from the same settings.
        return Pattern.compile(source.toString(), Pattern.MULTILINE);
    }

    /**
     * Writes the literal alternation grouped by first character.
     *
     * <p>{@code (?:alice|bob|carol)} makes the regex engine compare every literal in order at every
     * position of the text, so one line of scoreboard text cost the whole list at each character.
     * With one branch per distinct first character the engine rejects a position after a single
     * character comparison and only the literals sharing that character are tried in full.
     *
     * <p>Every literal keeps its own quoted spelling, so the grouping is a hint for the engine and
     * never decides what matches; case variants simply land in different branches, which the
     * case-insensitive literal branch accepts either way.
     */
    private static void appendLiteralAlternation(StringBuilder source, List<String> literals) {
        Map<Character, StringBuilder> tailsByHead = new LinkedHashMap<Character, StringBuilder>();
        Set<Character> bareHeads = new HashSet<Character>();
        for (int index = 0; index < literals.size(); index++) {
            String literal = literals.get(index);
            Character head = Character.valueOf(literal.charAt(0));
            if (literal.length() == 1) {
                // A one-character literal is the head on its own; an empty remainder alternative
                // would let the whole branch match zero characters.
                bareHeads.add(head);
                if (!tailsByHead.containsKey(head)) {
                    tailsByHead.put(head, new StringBuilder());
                }
                continue;
            }
            StringBuilder tails = tailsByHead.get(head);
            if (tails == null) {
                tails = new StringBuilder();
                tailsByHead.put(head, tails);
            }
            if (tails.length() > 0) {
                tails.append('|');
            }
            // Longest first, so the engine tries the longer form before any of its prefixes.
            tails.append(Pattern.quote(literal.substring(1)));
        }
        boolean first = true;
        for (Map.Entry<Character, StringBuilder> entry : tailsByHead.entrySet()) {
            if (!first) {
                source.append('|');
            }
            first = false;
            source.append(Pattern.quote(String.valueOf(entry.getKey().charValue())));
            StringBuilder tails = entry.getValue();
            if (tails.length() == 0) {
                continue;
            }
            source.append("(?:").append(tails);
            if (bareHeads.contains(entry.getKey())) {
                source.append('|');
            }
            source.append(')');
        }
    }

    /**
     * Returns the compiled pattern for an already deduplicated and sorted literal list, reusing a
     * previously compiled one when the same content has just been seen.
     *
     * <p>The key is the literal <em>content</em>, never the iterable identity: the platform
     * republishes an equal-but-new {@code List} every few seconds, so an identity key would never
     * hit. {@link #signature(List, boolean)} is only a fast reject filter; the entry is reused
     * solely when {@link List#equals(Object)} confirms that every literal matches, so a signature
     * collision can never hand back a pattern built from different literals.
     */
    private static Pattern cachedPattern(PreparedLiterals prepared) {
        PatternCacheEntry[] current = patternCache;
        for (int index = 0; index < current.length; index++) {
            PatternCacheEntry entry = current[index];
            if (entry != null && entry.matches(
                    prepared.signature, prepared.preserveHanText, prepared.literals)) {
                return entry.pattern;
            }
        }
        synchronized (PATTERN_CACHE_LOCK) {
            // Re-scan under the lock: a worker that missed outside may have been beaten to the
            // compilation by another thread, and compiling twice for one snapshot is the cost this
            // section exists to avoid.
            current = patternCache;
            for (int index = 0; index < current.length; index++) {
                PatternCacheEntry entry = current[index];
                if (entry != null && entry.matches(
                        prepared.signature, prepared.preserveHanText, prepared.literals)) {
                    return entry.pattern;
                }
            }
            Pattern compiled = compilePattern(prepared.literals, prepared.preserveHanText);
            PatternCacheEntry[] next = current.clone();
            next[patternCacheCursor] = new PatternCacheEntry(
                    prepared.signature, prepared.preserveHanText, prepared.literals, compiled);
            patternCacheCursor = (patternCacheCursor + 1) % next.length;
            patternCache = next;
            return compiled;
        }
    }

    /**
     * FNV-1a over the length and characters of every literal, with a separator between entries and
     * the Han flag mixed in first. Framing each literal with its length keeps {@code ["ab", "c"]}
     * apart from {@code ["a", "bc"]}, so equal-length-but-different content never shares a key.
     */
    private static long signature(List<String> literals, boolean preserveHanText) {
        long hash = FNV_OFFSET_BASIS;
        hash = (hash ^ (preserveHanText ? 1L : 0L)) * FNV_PRIME;
        for (int index = 0; index < literals.size(); index++) {
            String literal = literals.get(index);
            int length = literal.length();
            hash = (hash ^ length) * FNV_PRIME;
            hash = (hash ^ 0x1fL) * FNV_PRIME;
            for (int offset = 0; offset < length; offset++) {
                hash = (hash ^ literal.charAt(offset)) * FNV_PRIME;
            }
        }
        return hash;
    }

    private static final class PatternCacheEntry {
        private final long signature;
        private final boolean preserveHanText;
        private final List<String> literals;
        private final Pattern pattern;

        private PatternCacheEntry(
                long signature,
                boolean preserveHanText,
                List<String> literals,
                Pattern pattern
        ) {
            this.signature = signature;
            this.preserveHanText = preserveHanText;
            this.literals = Collections.unmodifiableList(new ArrayList<String>(literals));
            this.pattern = pattern;
        }

        private boolean matches(
                long candidateSignature,
                boolean candidateHan,
                List<String> candidate
        ) {
            return signature == candidateSignature
                    && preserveHanText == candidateHan
                    && literals.equals(candidate);
        }
    }

    /**
     * One literal snapshot in its usable form: deduplicated, longest first, and signed by content.
     *
     * <p>Immutable, so it can be shared by every worker of every session between two publications of
     * the snapshot. {@link #signature} is computed once here instead of once per request.
     */
    private static final class PreparedLiterals {
        private final Iterable<String> source;
        private final boolean preserveHanText;
        private final List<String> literals;
        private final long signature;
        /**
         * Entry count of {@code source} when it was prepared, or {@code -1} when the iterable cannot
         * report one; used to notice an in-place update of the published snapshot.
         */
        private final int sourceSize;

        private PreparedLiterals(
                Iterable<String> source,
                boolean preserveHanText,
                List<String> literals
        ) {
            this.source = source;
            this.preserveHanText = preserveHanText;
            this.literals = Collections.unmodifiableList(literals);
            this.signature = signature(this.literals, preserveHanText);
            this.sourceSize = sizeOf(source);
        }

        /**
         * Whether this prepared form still describes {@code candidate}.
         *
         * <p>Identity alone is not enough: the platform promises not to mutate a published snapshot,
         * and this check turns a violation into a rebuild for the shape such a violation takes in
         * practice (clear the list, refill it). An iterable that cannot report its size is trusted.
         */
        private boolean matches(Iterable<String> candidate, boolean candidateHan) {
            if (source != candidate || preserveHanText != candidateHan) {
                return false;
            }
            int candidateSize = sizeOf(candidate);
            return sourceSize < 0 || candidateSize < 0 || sourceSize == candidateSize;
        }
    }

    /** Entry count of an iterable, or {@code -1} when it cannot be asked without iterating it. */
    private static int sizeOf(Iterable<String> values) {
        return values instanceof Collection
                ? ((Collection<String>) values).size() : -1;
    }

    private static ProtectedText parseWithPattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        // Appending by hand keeps the scan on a plain StringBuilder: appendReplacement only exists
        // with a synchronized StringBuffer, and it also re-scans every token for '$' and '\' that a
        // token generated right here cannot contain.
        StringBuilder output = new StringBuilder(text.length() + 16);
        List<String> values = new ArrayList<String>();
        int cursor = 0;
        while (matcher.find()) {
            int index = values.size();
            values.add(matcher.group());
            output.append(text, cursor, matcher.start());
            appendToken(output, index);
            cursor = matcher.end();
        }
        output.append(text, cursor, text.length());
        return new ProtectedText(text, output.toString(), values);
    }

    public String getOriginal() {
        return original;
    }

    public String getTemplate() {
        return template;
    }

    public List<String> getValues() {
        return values;
    }

    String getUnprotectedTemplateText() {
        return splice().unprotectedText;
    }

    /** Returns exact protected values and translatable text as separate ordered parts. */
    List<Segment> getSegments() {
        return splice().segments;
    }

    /**
     * Runs the token scan once and keeps both views of its result.
     *
     * <p>The template used to be matched twice per request: once to build a token-free copy of the
     * whole text for a language heuristic, and once more to split the same template into segments.
     * This instance lives for one request, so the scan belongs to it.
     */
    private Splice splice() {
        Splice ready = splice;
        if (ready != null) {
            return ready;
        }
        synchronized (this) {
            ready = splice;
            if (ready == null) {
                ready = buildSplice();
                splice = ready;
            }
            return ready;
        }
    }

    private Splice buildSplice() {
        List<Segment> segments = new ArrayList<Segment>();
        StringBuilder unprotectedText = new StringBuilder(template.length());
        Matcher matcher = INTERNAL_TOKEN.matcher(template);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                String literal = template.substring(cursor, matcher.start());
                segments.add(new Segment(literal, false));
                unprotectedText.append(literal);
            }
            int index = tokenIndex(matcher.group());
            if (index >= 0 && index < values.size()) {
                segments.add(new Segment(values.get(index), true));
            } else {
                segments.add(new Segment(matcher.group(), false));
            }
            cursor = matcher.end();
        }
        if (cursor < template.length()) {
            String tail = template.substring(cursor);
            segments.add(new Segment(tail, false));
            unprotectedText.append(tail);
        }
        if (segments.isEmpty()) {
            segments.add(new Segment(original, false));
        }
        return new Splice(Collections.unmodifiableList(segments), unprotectedText.toString());
    }

    /** One token scan's two views: the ordered segments and the token-free copy of the template. */
    private static final class Splice {
        private final List<Segment> segments;
        private final String unprotectedText;

        private Splice(List<Segment> segments, String unprotectedText) {
            this.segments = segments;
            this.unprotectedText = unprotectedText;
        }
    }

    public String restore(String translatedTemplate) {
        // Single pass: copy template text verbatim and splice in the original values, so a value
        // that happens to contain a token literal (e.g. "__UT_1__") is never re-scanned.
        Matcher matcher = INTERNAL_TOKEN.matcher(translatedTemplate);
        StringBuilder output = new StringBuilder(translatedTemplate.length());
        int cursor = 0;
        while (matcher.find()) {
            output.append(translatedTemplate, cursor, matcher.start());
            int index = tokenIndex(matcher.group());
            if (index >= 0 && index < values.size()) {
                output.append(values.get(index));
            } else {
                output.append(matcher.group());
            }
            cursor = matcher.end();
        }
        output.append(translatedTemplate, cursor, translatedTemplate.length());
        return output.toString();
    }

    /** Writes {@code __UT_<index>__} without building the token as a separate {@code String}. */
    private static void appendToken(StringBuilder output, int index) {
        output.append(TOKEN_PREFIX).append(index).append(TOKEN_SUFFIX);
    }

    /**
     * Extracts the index from an internal token. Returns {@code -1} for indexes that do not fit in
     * an {@code int}, so an unexpected token is treated as literal text instead of failing.
     */
    private static int tokenIndex(String token) {
        try {
            return Integer.parseInt(token.substring(TOKEN_PREFIX.length(),
                    token.length() - TOKEN_SUFFIX.length()));
        } catch (NumberFormatException overflow) {
            return -1;
        }
    }

    static final class Segment {
        private final String text;
        private final boolean protectedValue;

        private Segment(String text, boolean protectedValue) {
            this.text = text;
            this.protectedValue = protectedValue;
        }

        String text() {
            return text;
        }

        boolean isProtectedValue() {
            return protectedValue;
        }
    }
}
