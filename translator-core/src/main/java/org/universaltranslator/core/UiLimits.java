package org.universaltranslator.core;

/**
 * Layout and input limits shared by every platform's screens.
 *
 * <p>These numbers were copied into each screen class, so raising a limit meant finding every
 * copy. They live here because the value is a product decision, not a per-version one.
 */
public final class UiLimits {
    /** Longest custom prompt the settings screen stores, in characters. */
    public static final int PROMPT_MAX_CHARS = 4096;

    /**
     * Longest custom prompt the settings screen shows in its single-line editor, in characters.
     *
     * <p>The editor text is the escaped form: every backslash and every line break becomes two
     * characters, so a stored prompt at the limit needs up to twice the field. Sizing the field with
     * {@link #PROMPT_MAX_CHARS} made the widget cut the escaped text and the next save wrote the cut
     * version back, which lost half of a multi-line prompt with no message.
     */
    public static final int PROMPT_EDITOR_MAX_CHARS = 2 * PROMPT_MAX_CHARS + 32;

    /**
     * Longest keyword text the blocklist field accepts, in characters.
     *
     * <p>Eight screens each wrote the literal {@code 4096} here, which happened to equal
     * {@link TranslationBlocklist#MAX_CONFIG_LENGTH}. Deriving it means raising the blocklist limit
     * cannot leave the field behind.
     */
    public static final int KEYWORDS_MAX_CHARS = TranslationBlocklist.MAX_CONFIG_LENGTH;

    /**
     * Number of keywords that actually take effect, and the number the hint text must name: anything
     * past this is discarded, so the screen has to say so instead of reporting a clean save.
     */
    public static final int KEYWORDS_MAX_ENTRIES = TranslationBlocklist.MAX_KEYWORDS;

    /** Longest single keyword that still matches; longer entries are cut to this length. */
    public static final int KEYWORD_MAX_CHARS = TranslationBlocklist.MAX_KEYWORD_LENGTH;

    /** Longest endpoint URL the settings screen accepts, in characters. */
    public static final int ENDPOINT_MAX_CHARS = 512;

    /** Longest API key or secret the settings screen accepts, in characters. */
    public static final int SECRET_MAX_CHARS = 512;

    /** Longest model identifier the settings screen accepts, in characters. */
    public static final int MODEL_ID_MAX_CHARS = 128;

    private UiLimits() {
    }
}
