package org.universaltranslator.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.OutgoingTranslationPreview;
import org.universaltranslator.core.TranslationActivity;
import org.universaltranslator.fabric.FabricTranslationRuntime;
import org.universaltranslator.fabric.TranslationRenderContext;

/**
 * The HUD translation indicator's painting, shared by every 26.x API layer.
 *
 * <p>The three {@code InGameHudStatusIndicatorMixin} copies only differ in their {@code @Mixin}
 * target ({@code Gui} up to 26.1.2, {@code Hud} from 26.2), so the 130-line body used to be
 * copy-pasted three times and a fix landed in one copy at a time — the 26.2 and 26.3 files were
 * byte-identical apart from a javadoc line. The body lives here once; a mixin keeps just its
 * {@code @Inject} skeleton.
 *
 * <p>It is not a mixin: it must not be listed in {@code universal-translator.fabric.mixins.json},
 * and it deliberately holds no state, because a mixin that declares fields risks colliding with
 * members of the class it targets.
 */
final class HudIndicatorPainter {
    /**
     * How long the localized activity label stays cached.
     *
     * <p>Resolving it costs a {@code TranslatableContents}, a language-table lookup and a fresh
     * {@code String} on every frame the indicator shows "translating". The player can change the
     * language while the game runs, so the value is refreshed once per second instead of being
     * fixed for the session.
     */
    private static final long ACTIVITY_LABEL_REFRESH_MILLIS = 1000L;
    private static String cachedActivityLabel;
    private static long cachedActivityLabelAt;
    /** The label's component, rebuilt only when the label string it wraps changes. */
    private static String cachedLabelValue;
    private static Component cachedLabelComponent;
    private static String cachedPreviewValue;
    private static Component cachedPreviewComponent;

    private HudIndicatorPainter() {
    }

    private static String activityLabel() {
        long now = System.currentTimeMillis();
        String cached = cachedActivityLabel;
        if (cached != null && now - cachedActivityLabelAt < ACTIVITY_LABEL_REFRESH_MILLIS) {
            return cached;
        }
        String resolved = Component.translatable(
                "value.universal_translator.hud_content.activity").getString();
        cachedActivityLabel = resolved;
        cachedActivityLabelAt = now;
        return resolved;
    }

    private static Component labelComponent(String label) {
        Component cached = cachedLabelComponent;
        if (cached != null && label.equals(cachedLabelValue)) {
            return cached;
        }
        Component created = Component.literal(label);
        cachedLabelValue = label;
        cachedLabelComponent = created;
        return created;
    }

    private static Component previewComponent(String preview) {
        Component cached = cachedPreviewComponent;
        if (cached != null && preview.equals(cachedPreviewValue)) {
            return cached;
        }
        Component created = Component.literal(preview);
        cachedPreviewValue = preview;
        cachedPreviewComponent = created;
        return created;
    }

    /** Draws the indicator square, the outgoing preview and the optional label for this frame. */
    static void paint(GuiGraphicsExtractor graphics, boolean hudHidden) {
        // The injection sits at the RETURN of the HUD render, which still runs while F1 hides the
        // HUD: without this the square and its label stayed on an otherwise empty screen and were
        // painted over the chat and the hotbar, because RETURN is the last thing that happens.
        //
        // The flag is passed in instead of read here: 26.1 to 26.1.2 keep the HUD-hidden flag on
        // {@code Gui.hideGui} while 26.2 and 26.3 renamed it to {@code Gui.hide}, and this file is
        // compiled once for all three targets, so a field access would only build for one of them.
        if (hudHidden) {
            return;
        }
        // Read the settings first: a disabled indicator draws nothing at all.
        // One read per frame: the runtime publishes a finished snapshot, and this method needs
        // four different fields off the same one.
        HomeQuickSettingsState quickSettings = FabricTranslationRuntime.homeSettings();
        HudIndicatorSettings settings = quickSettings.getHudIndicator();
        if (!settings.isIndicator()) {
            return;
        }
        switch (settings.getVisibility()) {
            case WHILE_TRANSLATING:
                if (!TranslationActivity.isTranslating()) {
                    return;
                }
                break;
            case WHEN_DISABLED:
                // The translation master switch, deliberately not the indicator's own option:
                // the indicator option already gated every value of this enum above.
                if (quickSettings.isEnabled()) {
                    return;
                }
                break;
            case ALWAYS:
            default:
                break;
        }
        HudIndicatorCorner corner = settings.getCorner();
        int margin = settings.getMargin();
        int size = settings.getSize();
        int left = corner.isRight() ? graphics.guiWidth() - margin - size : margin;
        int top = corner.isBottom() ? graphics.guiHeight() - margin - size : margin;
        // The drag offset comes from the settings screen, but the config file is plain text a
        // player can edit, so clamp the final position instead of trusting the stored range: the
        // indicator must never end up somewhere it cannot be dragged back from.
        left += settings.getOffsetX();
        top += settings.getOffsetY();
        left = Math.max(0, Math.min(graphics.guiWidth() - size, left));
        top = Math.max(0, Math.min(graphics.guiHeight() - size, top));
        int right = left + size;
        int bottom = top + size;
        int borderColor = 0xFF000000;
        // One pixel of opaque black keeps the indicator readable against sky and bright terrain.
        graphics.fill(left - 1, top - 1, right + 1, top, borderColor);
        graphics.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        graphics.fill(left - 1, top, left, bottom, borderColor);
        graphics.fill(right, top, right + 1, bottom, borderColor);
        boolean translationOn = quickSettings.isEnabled();
        if (translationOn) {
            // Solid colour while translation is on: configurable, green by default.
            graphics.fill(left, top, right, bottom, settings.getColor().argb());
        } else {
            // A hollow red frame while translation is off, so the two states differ in shape as well
            // as in colour and remain distinguishable for players who cannot separate the two hues.
            int disabledColor = 0xFFFF5555;
            graphics.fill(left, top, right, top + 1, disabledColor);
            graphics.fill(left, bottom - 1, right, bottom, disabledColor);
            graphics.fill(left, top + 1, left + 1, bottom - 1, disabledColor);
            graphics.fill(right - 1, top + 1, right, bottom - 1, disabledColor);
        }
        // The outgoing chat translation as far as it has been generated. Drawn from the same
        // render path as the indicator and only while that translation is still running, so the
        // player sees the answer arrive instead of waiting for the whole line.
        String outgoingPreview = OutgoingTranslationPreview.text();
        if (outgoingPreview != null) {
            int previewCenterX = graphics.guiWidth() / 2;
            int previewY = Math.max(0, graphics.guiHeight() - 60);
            Font font = Minecraft.getInstance().font;
            // Minecraft nulls the font while it reloads resources and the HUD keeps drawing, so
            // this frame has to skip the preview instead of measuring with nothing.
            if (font != null) {
                // This string is already the translated answer, and centeredText is itself hooked,
                // so it has to be marked as local text or the preview is translated again every
                // frame — with an English target language the player then sees the translation of
                // the translation drift from frame to frame. pushTextInput is the existing
                // mechanism for "what this mod draws on purpose" (see SignEditScreenMixin).
                TranslationRenderContext.pushTextInput();
                try {
                    graphics.centeredText(font, previewComponent(outgoingPreview),
                            previewCenterX, previewY, 0xFFFFFFFF);
                } finally {
                    TranslationRenderContext.popTextInput();
                }
            }
        }

        // The label is drawn in both states: the target language and the provider are settings, so
        // they stay informative even while translation is off.
        HudIndicatorContent content = settings.getContent();
        if (content != HudIndicatorContent.DOT) {
            String label = null;
            if (content == HudIndicatorContent.LANGUAGE) {
                label = quickSettings.getTargetLanguage();
            } else if (content == HudIndicatorContent.PROVIDER) {
                label = quickSettings.getProvider();
            } else if (content == HudIndicatorContent.ACTIVITY
                    && TranslationActivity.isTranslating()) {
                label = activityLabel();
            }
            if (label != null && !label.isEmpty()) {
                Font font = Minecraft.getInstance().font;
                if (font == null) {
                    return;
                }
                Component text = labelComponent(label);
                // Measure and draw under the same marker as the preview: the label is a setting
                // value or an already resolved lang string, and both font.width and centeredText
                // run through the hooked text paths.
                TranslationRenderContext.pushTextInput();
                try {
                    int labelWidth = font.width(text);
                    // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a
                    // label centred on a right-corner square would run off the screen edge. The
                    // square itself is clamped above, so the label has to be clamped too.
                    int labelCenterX = corner.isRight()
                            ? left - 2 - labelWidth / 2
                            : right + 2 + labelWidth / 2;
                    int minimumCentre = labelWidth / 2;
                    int maximumCentre = Math.max(minimumCentre, graphics.guiWidth() - labelWidth / 2);
                    labelCenterX = Math.max(minimumCentre, Math.min(maximumCentre, labelCenterX));
                    // y is the top of the text line, so centre it against the square by hand. A line
                    // is font.lineHeight tall, and the square can be smaller than one, in which
                    // case the label moves above the square instead of onto it.
                    int labelY = size >= font.lineHeight
                            ? top + (size - font.lineHeight) / 2
                            : Math.max(0, top - font.lineHeight);
                    graphics.centeredText(font, text, labelCenterX, labelY, 0xFFFFFFFF);
                } finally {
                    TranslationRenderContext.popTextInput();
                }
            }
        }
    }
}
