package org.universaltranslator.core;

/**
 * Log messages that more than one platform emits.
 *
 * <p>Log text stays in English on purpose: it is read in a crash report, not in the game UI, and
 * every platform logger takes a plain {@code String}. Keeping the wording here means a change
 * lands in one place instead of the thirty-odd client classes that share it.
 */
public final class LogMessages {
    /** Emitted when a platform refuses to open the settings screen. */
    public static final String SETTINGS_OPEN_FAILED = "Could not open MC Auto Translation Tool settings";

    /** Emitted when Mod Menu's config button cannot build the settings screen. */
    public static final String SETTINGS_OPEN_FAILED_FROM_MOD_MENU =
            "Could not open MC Auto Translation Tool settings from Mod Menu";

    private LogMessages() {
    }
}
