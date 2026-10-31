package org.universaltranslator.forge;

import net.minecraft.network.chat.Component;

/**
 * Frame-to-frame caches for the strings the HUD indicator writes.
 *
 * <p>The indicator is painted from {@code Gui.render}'s return, so every string it builds is built
 * sixty times a second: the activity label cost a language-table lookup plus a fresh {@code String}
 * per frame, and each label went back through {@code Font.width}, which walks every glyph. The
 * caches live here rather than in the mixin because the mixin deliberately declares no fields and no
 * helper methods: anything it adds is merged into {@code Gui} and can collide with a member there.
 *
 * <p>Every value is refreshed once per second instead of being fixed for the session, because the
 * player can change the language while the game runs.
 */
public final class HudStatusLabels {
    /** How long a cached value stays valid. */
    private static final long REFRESH_MILLIS = 1000L;

    private static String cachedActivity;
    private static long cachedActivityAt;
    private static String cachedLabelText;
    private static Component cachedLabel;
    private static long cachedLabelAt;

    private HudStatusLabels() {
    }

    /**
     * The localized "activity" label, resolved at most once per second.
     *
     * <p>Compared by presence as well as by age, so a language change inside the same second still
     * produces the new string on the next call rather than after the TTL.
     */
    public static String activity() {
        long now = System.currentTimeMillis();
        String cached = cachedActivity;
        if (cached != null && now - cachedActivityAt < REFRESH_MILLIS) {
            return cached;
        }
        String resolved = Component.translatable(
                "value.universal_translator.hud_content.activity").getString();
        cachedActivity = resolved;
        cachedActivityAt = now;
        return resolved;
    }

    /**
     * {@code text} as a component, built once per second per distinct string.
     *
     * <p>Keyed on the value: the language and provider labels only change when a setting does, and
     * the outgoing preview only while a translation is landing, so a static label stops allocating a
     * {@code MutableComponent} and a {@code Style} on every frame.
     */
    public static Component label(String text) {
        long now = System.currentTimeMillis();
        Component cached = cachedLabel;
        if (cached != null && text != null && text.equals(cachedLabelText)
                && now - cachedLabelAt < REFRESH_MILLIS) {
            return cached;
        }
        Component built = Component.literal(text == null ? "" : text);
        cachedLabelText = text;
        cachedLabel = built;
        cachedLabelAt = now;
        return built;
    }
}
