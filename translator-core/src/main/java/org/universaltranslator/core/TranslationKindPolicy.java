package org.universaltranslator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The content surfaces the player switched off one by one.
 *
 * <p>{@code translate-chat} and {@code translate-other} split the game into two halves: chat and
 * everything else. Twenty kinds of text live in that second half — signs, books, scoreboards, the
 * player list, item names, tooltips — and a player who only wanted translated chat had to give all
 * of them up together, while a player who wanted signs left alone had to stop all non-chat text.
 *
 * <p>This list narrows the two switches without changing what they mean: a kind is translated only
 * when its half is on <em>and</em> it is not named here. That order matters. Storing the exclusions
 * as "the user disabled the list" would make a later {@code translate-other} toggle pointless, and
 * storing them as separate booleans would put twenty more rows on a settings tab that already runs
 * out of height on a 256-pixel GUI scale.
 *
 * <p>The configured value accepts the surface names from {@link TextKind#key()} and a handful of
 * group names ({@link #groupNames()}), so {@code titles} covers title, subtitle, action bar, boss
 * bar and toast without five separate words. Parsing is deliberately forgiving of separators —
 * commas, semicolons, full-width punctuation, spaces and line breaks — because this is a value a
 * player types. Names that mean nothing are dropped and counted instead of being guessed at: a typo
 * that silently disabled the wrong surface would be worse than one that does nothing.
 */
public final class TranslationKindPolicy {
    /** Longest configured value that still takes effect, in characters. */
    public static final int MAX_CONFIG_LENGTH = 512;

    /** Number of entries that actually take effect; anything past this is dropped. */
    public static final int MAX_ENTRIES = 32;

    private static final TranslationKindPolicy NONE =
            new TranslationKindPolicy(Collections.<String>emptyList(), Collections.<TextKind>emptySet());

    /** Group names that stand for several surfaces at once, in the order the hint lists them. */
    private static final Map<String, Set<TextKind>> GROUPS = groups();

    private final List<String> entries;
    private final Set<TextKind> disabled;

    private TranslationKindPolicy(List<String> entries, Set<TextKind> disabled) {
        this.entries = entries;
        this.disabled = disabled;
    }

    /** Nothing excluded: every surface its half allows is translated. */
    public static TranslationKindPolicy none() {
        return NONE;
    }

    public static TranslationKindPolicy parse(String configured) {
        return parseWithReport(configured).policy();
    }

    /** Parses the configured value and reports the entries that had to be discarded. */
    public static Parsed parseWithReport(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return new Parsed(NONE, 0, 0, 0);
        }
        String bounded = configured;
        int droppedForLength = 0;
        if (bounded.length() > MAX_CONFIG_LENGTH) {
            // Cut on a separator, not mid-token: a half-written token would name nothing and be
            // dropped anyway, but the count would then blame the wrong loss.
            int boundary = lastSeparatorBefore(bounded, MAX_CONFIG_LENGTH);
            int cut = boundary > 0 ? boundary : MAX_CONFIG_LENGTH;
            droppedForLength = countEntries(bounded, cut);
            bounded = bounded.substring(0, cut);
        }
        String[] candidates = bounded.split("[,;\\s，；、]+");
        List<String> entries = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        Set<TextKind> disabled = EnumSet.noneOf(TextKind.class);
        int unknown = 0;
        int dropped = 0;
        for (String candidate : candidates) {
            String token = candidate.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if (token.isEmpty()) {
                continue;
            }
            Set<TextKind> group = GROUPS.get(token);
            TextKind single = group == null ? TextKind.fromKey(token) : null;
            if (group == null && single == null) {
                unknown++;
                continue;
            }
            if (seen.contains(token)) {
                continue;
            }
            if (seen.size() >= MAX_ENTRIES) {
                dropped++;
                continue;
            }
            seen.add(token);
            entries.add(token);
            if (group != null) {
                disabled.addAll(group);
            } else {
                disabled.add(single);
            }
        }
        if (entries.isEmpty()) {
            return new Parsed(NONE, unknown, dropped, droppedForLength);
        }
        return new Parsed(new TranslationKindPolicy(
                Collections.unmodifiableList(entries),
                Collections.unmodifiableSet(disabled)), unknown, dropped, droppedForLength);
    }

    /** Whether {@code kind} survives the coarse switches this list narrows. */
    public boolean allows(TextKind kind) {
        return kind == null || !disabled.contains(kind);
    }

    /** Number of individual surfaces the configured entries exclude. */
    public int disabledCount() {
        return disabled.size();
    }

    /**
     * The value to write back, with the entries as they were typed.
     *
     * <p>Groups are kept as groups rather than expanded to their members: a player who wrote
     * {@code titles} should not open the file and find five words, because the group is the thing
     * they meant and the members are an implementation detail that may grow.
     */
    public String storageForm() {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String entry : entries) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(entry);
        }
        return builder.toString();
    }

    /** Canonical form of a configured value; used by every save path. */
    public static String normalizeForStorage(String configured) {
        return parse(configured).storageForm();
    }

    /** Group names the hint text can list. */
    public static String[] groupNames() {
        return GROUPS.keySet().toArray(new String[0]);
    }

    /** Every name the field accepts: groups first, then the individual surfaces. */
    public static String[] entries() {
        List<String> names = new ArrayList<String>(GROUPS.keySet());
        for (TextKind kind : TextKind.values()) {
            names.add(kind.key());
        }
        return names.toArray(new String[0]);
    }

    private static Map<String, Set<TextKind>> groups() {
        Map<String, Set<TextKind>> groups = new LinkedHashMap<String, Set<TextKind>>();
        groups.put("chat", EnumSet.of(TextKind.CHAT, TextKind.SYSTEM_MESSAGE));
        groups.put("scoreboard", EnumSet.of(TextKind.SCOREBOARD_TITLE, TextKind.SCOREBOARD_LINE));
        groups.put("player-list", EnumSet.of(TextKind.PLAYER_LIST_HEADER, TextKind.PLAYER_LIST_FOOTER));
        groups.put("titles", EnumSet.of(TextKind.TITLE, TextKind.SUBTITLE, TextKind.ACTION_BAR,
                TextKind.BOSS_BAR, TextKind.TOAST));
        groups.put("items", EnumSet.of(TextKind.CONTAINER_TITLE, TextKind.ITEM_NAME, TextKind.ITEM_LORE,
                TextKind.TOOLTIP));
        groups.put("world", EnumSet.of(TextKind.SIGN, TextKind.BOOK, TextKind.HOLOGRAM,
                TextKind.ENTITY_NAME));
        groups.put("misc", EnumSet.of(TextKind.DISCONNECT_REASON, TextKind.OTHER));
        return Collections.unmodifiableMap(groups);
    }

    private static int countEntries(String text, int from) {
        int lost = 0;
        int index = from;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (isSeparator(character)) {
                index++;
                continue;
            }
            lost++;
            while (index < text.length() && !isSeparator(text.charAt(index))) {
                index++;
            }
        }
        return lost;
    }

    private static int lastSeparatorBefore(String text, int limit) {
        int bounded = Math.min(limit, text.length() - 1);
        for (int index = bounded; index >= 0; index--) {
            if (isSeparator(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isSeparator(char character) {
        return character == ',' || character == ';' || character == ':'
                || Character.isWhitespace(character)
                || character == '\uff0c' || character == '\uff1b' || character == '\u3001';
    }

    /** One parse outcome plus the losses the screen has to report. */
    public static final class Parsed {
        private final TranslationKindPolicy policy;
        private final int unknown;
        private final int dropped;
        private final int droppedForLength;

        private Parsed(TranslationKindPolicy policy, int unknown, int dropped, int droppedForLength) {
            this.policy = policy;
            this.unknown = unknown;
            this.dropped = dropped;
            this.droppedForLength = droppedForLength;
        }

        public TranslationKindPolicy policy() {
            return policy;
        }

        /** Entries that named no surface and no group; they have no effect. */
        public int unknown() {
            return unknown;
        }

        /** Entries ignored because {@link #MAX_ENTRIES} was already reached. */
        public int dropped() {
            return dropped;
        }

        /** Entries removed because the configured value exceeded {@link #MAX_CONFIG_LENGTH}. */
        public int droppedForLength() {
            return droppedForLength;
        }

        /** Whether part of the configured list has no effect. */
        public boolean lossy() {
            return unknown > 0 || dropped > 0 || droppedForLength > 0;
        }
    }
}
