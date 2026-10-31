package org.universaltranslator.forge;

import net.minecraft.network.chat.Component;
import org.universaltranslator.core.HudLabelCache;

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
    // The interval and the activity key live in HudLabelCache so this cache and the three sibling
    // copies cannot drift apart; only the translation call differs per loader.
    private static String cachedActivity;
    private static long cachedActivityAt;
    private static String cachedLabelText;
    private static Component cachedLabel;
    private static long cachedLabelAt;
    /**
     * The outgoing preview's own slot.
     *
     * <p>The indicator draws the preview and the content label in the same frame, so one shared slot
     * made each call evict what the other had just built and both were rebuilt every frame — the
     * allocation the cache exists to remove.
     */
    private static String cachedPreviewText;
    private static Component cachedPreview;
    private static long cachedPreviewAt;

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
        if (cached != null && now - cachedActivityAt < HudLabelCache.REFRESH_MILLIS) {
            return cached;
        }
        String resolved = Component.translatable(HudLabelCache.ACTIVITY_KEY).getString();
        cachedActivity = resolved;
        cachedActivityAt = now;
        return resolved;
    }

    /**
     * {@code text} as a component, built once per second per distinct string.
     *
     * <p>Keyed on the value: the language and provider labels only change when a setting does, so a
     * static label stops allocating a {@code MutableComponent} and a {@code Style} on every frame.
     * This is the content label's slot; {@link #preview(String)} is the outgoing preview's.
     */
    public static Component label(String text) {
        long now = System.currentTimeMillis();
        Component cached = cachedLabel;
        if (cached != null && text != null && text.equals(cachedLabelText)
                && now - cachedLabelAt < HudLabelCache.REFRESH_MILLIS) {
            return cached;
        }
        Component built = Component.literal(text == null ? "" : text);
        cachedLabelText = text;
        cachedLabel = built;
        cachedLabelAt = now;
        return built;
    }

    /**
     * {@code text} as a component for the outgoing chat preview, which has its own slot.
     *
     * <p>Same one-second, value-keyed rule as {@link #label(String)}; the preview only changes while
     * a translation is landing, so this keeps it to one build per distinct value.
     */
    public static Component preview(String text) {
        long now = System.currentTimeMillis();
        Component cached = cachedPreview;
        if (cached != null && text != null && text.equals(cachedPreviewText)
                && now - cachedPreviewAt < HudLabelCache.REFRESH_MILLIS) {
            return cached;
        }
        Component built = Component.literal(text == null ? "" : text);
        cachedPreviewText = text;
        cachedPreview = built;
        cachedPreviewAt = now;
        return built;
    }
}
