package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.resource.language.I18n;
import org.universaltranslator.core.HudLabelCache;

/**
 * Frame-to-frame caches for what the HUD indicator writes.
 *
 * <p>The indicator is painted from {@code InGameHud.render}'s return, so every string it builds is
 * built sixty times a second: the activity label cost a language-table lookup and a fresh
 * {@code String} per frame, and each label went back through {@code TextRenderer.getWidth}, which
 * walks every glyph of the string.
 *
 * <p>String-valued on purpose. The shared 1.16.2-1.19.2 layer spans the 1.19 rename that removed
 * {@code TranslatableText}, so a translated component cannot be built here at all: the activity
 * label comes from {@code I18n}, the one lookup API present in every version this layer is compiled
 * against.
 *
 * <p>The caches live here rather than in the mixin because the mixin deliberately declares no
 * fields: a field it adds is merged into {@code InGameHud} and can collide with a member of the
 * class it targets.
 */
final class HudLabels {
    // The interval and the activity key live in HudLabelCache so this cache and the three sibling
    // copies cannot drift apart. Nothing here holds on to the TextRenderer either: keeping the old
    // instance to key the width on would have pinned the previous font for as long as the game runs.
    private static String cachedActivity;
    private static long cachedActivityAt;
    /**
     * Last measured strings and their widths.
     *
     * <p>Two slots, not one: the preview and the label are measured on every frame the indicator
     * shows both, so a single slot would be overwritten by the other string each frame and never
     * hit.
     */
    private static final int WIDTH_SLOTS = 2;
    private static final String[] widthText = new String[WIDTH_SLOTS];
    private static final int[] widthValue = new int[WIDTH_SLOTS];
    private static final long[] widthAt = new long[WIDTH_SLOTS];
    private static int widthNext;

    private HudLabels() {
    }

    /**
     * The localized "activity" label, resolved at most once per second.
     *
     * <p>Compared by value as well as by age: a language change inside the same second still
     * produces the new string on the next call rather than after the TTL.
     */
    static String activity() {
        long now = System.currentTimeMillis();
        String cached = cachedActivity;
        if (cached != null && now - cachedActivityAt < HudLabelCache.REFRESH_MILLIS) {
            return cached;
        }
        String resolved = I18n.translate(HudLabelCache.ACTIVITY_KEY);
        cachedActivity = resolved;
        cachedActivityAt = now;
        return resolved;
    }

    /**
     * Width of {@code text} on {@code renderer}, measured once per second per distinct string.
     *
     * <p>Drawing the label and measuring it happen on the same frame the next frame too, and the
     * measurement is the expensive half. The key is the string itself, so nothing is retained and a
     * resource reload is corrected within one refresh at most.
     */
    static int width(TextRenderer renderer, String text) {
        long now = System.currentTimeMillis();
        for (int slot = 0; slot < WIDTH_SLOTS; slot++) {
            if (text != null && text.equals(widthText[slot])
                    && now - widthAt[slot] < HudLabelCache.REFRESH_MILLIS) {
                return widthValue[slot];
            }
        }
        int measured = renderer == null || text == null ? 0 : renderer.getWidth(text);
        int slot = widthNext;
        widthNext = (slot + 1) % WIDTH_SLOTS;
        widthText[slot] = text;
        widthValue[slot] = measured;
        widthAt[slot] = now;
        return measured;
    }
}
