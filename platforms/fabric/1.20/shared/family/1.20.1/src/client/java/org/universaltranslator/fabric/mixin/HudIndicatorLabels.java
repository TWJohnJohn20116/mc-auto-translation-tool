package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;
import org.universaltranslator.core.HudLabelCache;

/**
 * Frame-to-frame caches for what the HUD indicator writes on 1.20.x.
 *
 * <p>The indicator is painted from {@code InGameHud.render}'s return, so every string it builds is
 * built sixty times a second. The activity label used to cost a language-table lookup and a fresh
 * {@code String} per frame, and the label and outgoing-preview components were rebuilt on every
 * frame even when their text had not changed.
 *
 * <p>The caches live here rather than in the mixin because the mixin deliberately declares no
 * fields: a field it adds is merged into {@code InGameHud} and can collide with a member of the
 * class it targets.
 */
final class HudIndicatorLabels {
    // The refresh interval and the activity key live in HudLabelCache so this cache and the three
    // sibling copies cannot drift apart; only the translation call differs per generation.
    private static String cachedActivity;
    private static long cachedActivityAt;
    private static String cachedLabelValue;
    private static Text cachedLabelText;
    private static String cachedPreviewValue;
    private static Text cachedPreviewText;
    /**
     * Last measured strings and their widths.
     *
     * <p>The same one-second refresh as the activity label: the player can change the language or
     * the GUI scale while the game runs, and a resource reload swaps the font.
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

    private HudIndicatorLabels() {
    }

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

    /**
     * Width of {@code text} on {@code renderer}, measured once per second per distinct string.
     *
     * <p>Measuring walks every glyph of the string, so it is the expensive half of drawing the
     * label; the key is the string itself, so nothing is retained and a font swap is corrected
     * within one refresh at most.
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
