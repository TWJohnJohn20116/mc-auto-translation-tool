package org.universaltranslator.core;

import java.util.Locale;

/** The screen or world surface on which server-provided text appears. */
public enum TextKind {
    CHAT,
    SYSTEM_MESSAGE,
    SCOREBOARD_TITLE,
    SCOREBOARD_LINE,
    PLAYER_LIST_HEADER,
    PLAYER_LIST_FOOTER,
    ACTION_BAR,
    TITLE,
    SUBTITLE,
    BOSS_BAR,
    CONTAINER_TITLE,
    ITEM_NAME,
    ITEM_LORE,
    TOOLTIP,
    SIGN,
    BOOK,
    HOLOGRAM,
    ENTITY_NAME,
    DISCONNECT_REASON,
    TOAST,
    OTHER;

    /**
     * Stable configuration name for this surface.
     *
     * <p>This is what {@code disabled-kinds} stores and what the settings hint prints, so the
     * spelling has to survive releases: renaming a key silently switches a surface the player had
     * turned off back on, with nothing on screen to explain why the text reappeared. The enum
     * constant name is not used directly — {@code CHAT} reads as shouting inside a text field, and
     * the hyphenated form is how the keyword list already writes multi-word values.
     */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Resolves a configured surface name, or {@code null} when the text names none.
     *
     * <p>Underscores are accepted as well, because the enum spelling is what a player reading the
     * source or an older log would copy. Surrounding whitespace is ignored: the value arrives from a
     * text field where a trailing space is invisible.
     */
    public static TextKind fromKey(String configured) {
        if (configured == null) {
            return null;
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.isEmpty()) {
            return null;
        }
        for (TextKind kind : values()) {
            if (kind.key().equals(normalized)) {
                return kind;
            }
        }
        return null;
    }
}
