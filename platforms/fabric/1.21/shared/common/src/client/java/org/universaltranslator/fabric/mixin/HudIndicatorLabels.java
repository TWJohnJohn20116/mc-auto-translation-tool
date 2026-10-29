package org.universaltranslator.fabric.mixin;

import net.minecraft.text.Text;

/**
 * Frame-to-frame caches for what the HUD indicator writes.
 *
 * <p>The indicator is painted from {@code InGameHud.render}'s return, so every string it builds is
 * built sixty times a second. The activity label used to cost a {@code TranslatableContents}, a
 * language-table lookup and a fresh {@code String} per frame, and the label and outgoing-preview
 * components were rebuilt on every frame even when their text had not changed.
 *
 * <p>The caches live here rather than in the mixin because the mixin deliberately declares no
 * fields: a field it adds is merged into {@code InGameHud} and can collide with a member of the
 * class it targets.
 */
final class HudIndicatorLabels {
    /**
     * How long the localized activity label stays cached.
     *
     * <p>The player can change the language while the game runs, so the value is refreshed once per
     * second instead of being fixed for the session.
     */
    private static final long ACTIVITY_REFRESH_MILLIS = 1000L;
    private static String cachedActivity;
    private static long cachedActivityAt;
    private static String cachedLabelValue;
    private static Text cachedLabelText;
    private static String cachedPreviewValue;
    private static Text cachedPreviewText;

    private HudIndicatorLabels() {
    }

    static String activity() {
        long now = System.currentTimeMillis();
        String cached = cachedActivity;
        if (cached != null && now - cachedActivityAt < ACTIVITY_REFRESH_MILLIS) {
            return cached;
        }
        String resolved = Text.translatable(
                "value.universal_translator.hud_content.activity").getString();
        cachedActivity = resolved;
        cachedActivityAt = now;
        return resolved;
    }

    static Text label(String label) {
        Text cached = cachedLabelText;
        if (cached != null && label.equals(cachedLabelValue)) {
            return cached;
        }
        Text created = Text.literal(label);
        cachedLabelValue = label;
        cachedLabelText = created;
        return created;
    }

    static Text preview(String preview) {
        Text cached = cachedPreviewText;
        if (cached != null && preview.equals(cachedPreviewValue)) {
            return cached;
        }
        Text created = Text.literal(preview);
        cachedPreviewValue = preview;
        cachedPreviewText = created;
        return created;
    }
}
