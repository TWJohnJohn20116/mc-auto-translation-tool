package org.universaltranslator.fabric.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.OutgoingTranslationPreview;
import org.universaltranslator.core.TranslationActivity;
import org.universaltranslator.fabric.FabricTranslationRuntime;
import org.universaltranslator.fabric.TranslationRenderContext;

/**
 * Paints a small translation status indicator on the HUD so the player can tell at a glance whether
 * translation is currently on.
 *
 * <p>Everything it draws comes from {@code HudIndicatorSettings}: the corner, size, margin, colour,
 * what appears inside the square, and when it is drawn at all. Only the optional label needs a lang
 * entry; the rest is rectangles and literal text.
 *
 * <p>All state lives in locals on purpose: a mixin that declares extra fields or helper methods
 * risks colliding with members of the target class.
 */
@Mixin(InGameHud.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(
            method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
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
        // The injection sits at the RETURN of the HUD render, which still runs while F1 hides the
        // HUD: without this the square and its label stayed on an otherwise empty screen and were
        // painted over the chat and the hotbar, because RETURN is the last thing that happens.
        if (MinecraftClient.getInstance().options.hudHidden) {
            return;
        }
        HudIndicatorCorner corner = settings.getCorner();
        int margin = settings.getMargin();
        int size = settings.getSize();
        int left = corner.isRight() ? context.getScaledWindowWidth() - margin - size : margin;
        int top = corner.isBottom() ? context.getScaledWindowHeight() - margin - size : margin;
        // The drag offset comes from the settings screen, but the config file is plain text a
        // player can edit, so clamp the final position instead of trusting the stored range: the
        // indicator must never end up somewhere it cannot be dragged back from.
        left += settings.getOffsetX();
        top += settings.getOffsetY();
        left = Math.max(0, Math.min(context.getScaledWindowWidth() - size, left));
        top = Math.max(0, Math.min(context.getScaledWindowHeight() - size, top));
        int right = left + size;
        int bottom = top + size;
        int borderColor = 0xFF000000;
        // One pixel of opaque black keeps the indicator readable against sky and bright terrain.
        context.fill(left - 1, top - 1, right + 1, top, borderColor);
        context.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        context.fill(left - 1, top, left, bottom, borderColor);
        context.fill(right, top, right + 1, bottom, borderColor);
        boolean translationOn = quickSettings.isEnabled();
        if (translationOn) {
            // Solid colour while translation is on: configurable, green by default.
            context.fill(left, top, right, bottom, settings.getColor().argb());
        } else {
            // A hollow red frame while translation is off, so the two states differ in shape as well
            // as in colour and remain distinguishable for players who cannot separate the two hues.
            int disabledColor = 0xFFFF5555;
            context.fill(left, top, right, top + 1, disabledColor);
            context.fill(left, bottom - 1, right, bottom, disabledColor);
            context.fill(left, top + 1, left + 1, bottom - 1, disabledColor);
            context.fill(right - 1, top + 1, right, bottom - 1, disabledColor);
        }
        // The outgoing chat translation as far as it has been generated. Drawn from the same
        // render path as the indicator and only while that translation is still running, so the
        // player sees the answer arrive instead of waiting for the whole line.
        String outgoingPreview = OutgoingTranslationPreview.text();
        if (outgoingPreview != null) {
            int previewCenterX = context.getScaledWindowWidth() / 2;
            int previewY = Math.max(0, context.getScaledWindowHeight() - 60);
            net.minecraft.client.font.TextRenderer previewFont =
                    net.minecraft.client.MinecraftClient.getInstance().textRenderer;
            // The text renderer is null while resources reload and the HUD keeps drawing through
            // that window, so this frame has to skip the preview instead of measuring with nothing.
            if (previewFont != null) {
                // This string is already the translated answer, and every draw call below is itself
                // hooked, so it has to be marked as local text or the preview is translated again
                // on every frame — an English target language passes the filter and the player
                // watches the translation of the translation drift. pushTextInput is the existing
                // mechanism (see SignEditScreenMixin) for "what this mod draws on purpose".
                TranslationRenderContext.pushTextInput();
                try {
                    context.drawCenteredTextWithShadow(
                            previewFont,
                            HudIndicatorLabels.preview(outgoingPreview),
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
                label = HudIndicatorLabels.activity();
            }
            if (label != null && !label.isEmpty()) {
                net.minecraft.client.font.TextRenderer font =
                        net.minecraft.client.MinecraftClient.getInstance().textRenderer;
                if (font == null) {
                    return;
                }
                net.minecraft.text.Text text = HudIndicatorLabels.label(label);
                // Measure and draw under the same "this is local text" marker as the preview: the
                // label is a setting value or an already resolved lang string, and both the width
                // call and the draw call run through the hooked text renderer.
                TranslationRenderContext.pushTextInput();
                int labelWidth;
                try {
                    labelWidth = HudIndicatorLabels.width(font, text, label);
                    // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a
                    // label centred on a right-corner square would run off the screen edge. The
                    // square itself is clamped above, so the label has to be clamped too.
                    int labelCenterX = corner.isRight()
                            ? left - 2 - labelWidth / 2
                            : right + 2 + labelWidth / 2;
                    int minimumCentre = labelWidth / 2;
                    int maximumCentre = Math.max(minimumCentre,
                            context.getScaledWindowWidth() - labelWidth / 2);
                    labelCenterX = Math.max(minimumCentre, Math.min(maximumCentre, labelCenterX));
                    // y is the top of the text line, so centre it against the square by hand. A line
                    // is font.fontHeight tall, and the square can be smaller than one, in which case
                    // the label moves above the square instead of onto it.
                    int labelY = size >= font.fontHeight
                            ? top + (size - font.fontHeight) / 2
                            : Math.max(0, top - font.fontHeight);
                    context.drawCenteredTextWithShadow(font, text, labelCenterX, labelY, 0xFFFFFFFF);
                } finally {
                    TranslationRenderContext.popTextInput();
                }
            }
        }
    }
}
