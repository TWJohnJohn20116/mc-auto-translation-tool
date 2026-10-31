package org.universaltranslator.forge;

import org.universaltranslator.core.HudLabelCache;

/**
 * Frame-to-frame caches for the strings the HUD indicator writes.
 *
 * <p>The indicator is painted from the {@code render} return of the in-game HUD, so every string it
 * builds is built sixty times a second: the activity label cost a language-table lookup plus a fresh
 * {@code String} per frame, each drawn label allocated a component and its style, and every label went
 * back through {@code font.width}, which walks every glyph. The caches live here rather than in the
 * mixins because a mixin that declares fields or helper methods risks colliding with members of the
 * class it is merged into.
 *
 * <p>The four Forge modern generations disagree about the component type (1.16.5 and 1.18.2 build a
 * {@code TextComponent}, 1.19.2 and 1.20.1 build a {@code Component}), so the component slots hold
 * {@code Object} and the caller hands the value back to its own draw call: what is cached is the
 * instance the caller itself built, and the resolution call stays in the generation that can make it.
 *
 * <p>Every slot is refreshed once per second instead of being fixed for the session, because the
 * player can change the language while the game runs and a resource reload swaps the font the labels
 * are measured with. Nothing here has to be cleared: each slot holds one value and one timestamp, so
 * the footprint is a handful of references for the lifetime of the process.
 */
public final class HudLabels {
    private static String activityText;
    private static long activityResolvedAt;
    private static String labelText;
    private static Object labelComponent;
    private static long labelAt;
    /**
     * The outgoing preview's own slot.
     *
     * <p>The indicator draws the preview and the content label in the same frame, so one shared slot
     * made each call evict what the other had just built and both were rebuilt every frame -- the
     * allocation these caches exist to remove.
     */
    private static String previewText;
    private static Object previewComponent;
    private static long previewAt;
    private static String widthText;
    private static int width;
    private static long widthAt;

    private HudLabels() {
    }

    /** True when the activity label has to be resolved again: never resolved, or older than the TTL. */
    public static boolean activityExpired(long nowMillis) {
        return activityText == null
                || nowMillis - activityResolvedAt >= HudLabelCache.REFRESH_MILLIS;
    }

    /** Records a freshly resolved activity label; {@link #activity()} answers it until the next TTL. */
    public static void rememberActivity(String resolved, long nowMillis) {
        activityText = resolved;
        activityResolvedAt = nowMillis;
    }

    /** The activity label recorded by {@link #rememberActivity}, or {@code null} before the first one. */
    public static String activity() {
        return activityText;
    }

    /**
     * The component already built for {@code text} as a content label, or {@code null} when the caller
     * has to build one. Cast the answer to this generation's component type.
     */
    public static Object label(String text, long nowMillis) {
        return usable(labelComponent, labelText, labelAt, text, nowMillis) ? labelComponent : null;
    }

    /** Keeps a freshly built label component; {@link #label(String, long)} answers it until the next TTL. */
    public static void rememberLabel(String text, Object component, long nowMillis) {
        labelText = text;
        labelComponent = component;
        labelAt = nowMillis;
    }

    /** The component already built for {@code text} as the outgoing preview, or {@code null}. */
    public static Object preview(String text, long nowMillis) {
        return usable(previewComponent, previewText, previewAt, text, nowMillis)
                ? previewComponent : null;
    }

    /** Keeps a freshly built preview component; {@link #preview(String, long)} answers it until the TTL. */
    public static void rememberPreview(String text, Object component, long nowMillis) {
        previewText = text;
        previewComponent = component;
        previewAt = nowMillis;
    }

    /**
     * The width already measured for {@code text}, or {@code -1} when the caller has to measure it.
     *
     * <p>Keyed on the text as well as the age: the label changes when the target language or the
     * provider does, and the font changes on a resource reload.
     */
    public static int width(String text, long nowMillis) {
        if (widthText == null || text == null || !text.equals(widthText)
                || nowMillis - widthAt >= HudLabelCache.REFRESH_MILLIS) {
            return -1;
        }
        return width;
    }

    /** Keeps a freshly measured label width; {@link #width(String, long)} answers it until the TTL. */
    public static void rememberWidth(String text, int measured, long nowMillis) {
        widthText = text;
        width = measured;
        widthAt = nowMillis;
    }

    /** Whether a cached value still describes {@code text} and is inside the TTL. */
    private static boolean usable(Object cached, String cachedText, long cachedAt, String text,
            long nowMillis) {
        return cached != null && text != null && text.equals(cachedText)
                && nowMillis - cachedAt < HudLabelCache.REFRESH_MILLIS;
    }
}
