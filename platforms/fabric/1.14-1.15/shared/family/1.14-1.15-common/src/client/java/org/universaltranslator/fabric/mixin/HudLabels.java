package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.TranslatableText;
import org.universaltranslator.core.HudLabelCache;

/**
 * Frame-to-frame caches for what the HUD indicator writes on 1.14.3-1.15.2.
 *
 * <p>The indicator is painted from the RETURN of the HUD render, so every string it builds is built
 * sixty times a second: the activity label cost a fresh {@code TranslatableText} plus the lookup
 * behind {@code getString} per frame, and each label went back through
 * {@code TextRenderer.getStringWidth}, which walks every glyph of the string.
 *
 * <p>Its own class rather than fields on the mixin: a field the mixin adds is merged into
 * {@code InGameHud} and can collide with a member of the class it targets. The two 1.14/1.15 mixins
 * of this family share this copy because both mapping sets name the measuring method
 * {@code getStringWidth} and the component {@code TranslatableText}.
 */
final class HudLabels {
    /**
     * How long a cached value stays valid.
     *
     * <p>The player can change the language while the game runs and a resource reload swaps the font,
     * so both values are refreshed once per second instead of being fixed for the session. Nothing
     * here holds on to the {@code TextRenderer}: keeping the old instance to key the width on would
     * pin the previous font for as long as the game runs.
     */
    private static final long REFRESH_MILLIS = HudLabelCache.REFRESH_MILLIS;
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
     * <p>Compared by age only in this generation: {@code TranslatableText} is the one component type
     * present on every version this family compiles against.
     */
    static String activity() {
        long now = System.currentTimeMillis();
        String cached = cachedActivity;
        if (cached != null && now - cachedActivityAt < REFRESH_MILLIS) {
            return cached;
        }
        String resolved = new TranslatableText(HudLabelCache.ACTIVITY_KEY).getString();
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
            if (text != null && text.equals(widthText[slot]) && now - widthAt[slot] < REFRESH_MILLIS) {
                return widthValue[slot];
            }
        }
        int measured = renderer == null || text == null ? 0 : renderer.getStringWidth(text);
        int slot = widthNext;
        widthNext = (slot + 1) % WIDTH_SLOTS;
        widthText[slot] = text;
        widthValue[slot] = measured;
        widthAt[slot] = now;
        return measured;
    }
}
