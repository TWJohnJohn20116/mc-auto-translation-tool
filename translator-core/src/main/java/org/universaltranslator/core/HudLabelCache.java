package org.universaltranslator.core;

/**
 * The single key and the single refresh interval behind the HUD indicator's activity label.
 *
 * <p>Every platform generation keeps its own small cache beside the mixin that paints the label,
 * because the translation call itself is not portable: 1.16–1.19 has {@code I18n.translate}, 1.20
 * keeps {@code I18n} next to {@code Text}, and 1.21/Forge build a {@code Text} or
 * {@code Component} instead. The key they resolve and the interval they refresh on are the same
 * values in all of them, so those two live here — otherwise changing either means editing four
 * files that nothing compares.
 */
public final class HudLabelCache {
    /** The activity label the HUD indicator shows next to the hot bar. */
    public static final String ACTIVITY_KEY = "value.universal_translator.hud_content.activity";

    /**
     * How long a cached HUD label stays valid.
     *
     * <p>One second rather than the whole session: the player can change the language while the
     * game runs, and a resource reload swaps the font the labels are measured with.
     */
    public static final long REFRESH_MILLIS = 1000L;

    private HudLabelCache() {
    }
}
