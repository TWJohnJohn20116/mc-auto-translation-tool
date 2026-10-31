package org.universaltranslator.fabric.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
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
 * what appears inside the square, and when it is drawn at all.
 *
 * <p>1.20 replaced the {@code MatrixStack} render parameter with {@code DrawContext} and made
 * {@code InGameHud} stop extending {@code DrawableHelper}, so this file draws through the context
 * instead. The window size still comes from {@code MinecraftClient.getWindow()}: that accessor is
 * identical across 1.16-1.20.6, and {@code InGameHud}'s own scaled-width fields are private.
 *
 * <p>The label and the outgoing preview are localized through {@code HudIndicatorLabels}, which
 * caches them: the HUD redraws sixty times a second, and a language lookup plus a fresh
 * {@code Text} per frame is pure waste while the value has not changed.
 *
 * <p>All state lives in locals on purpose: a mixin that declares extra fields or helper methods
 * risks colliding with members of the target class.
 */
@Mixin(InGameHud.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;F)V", at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            DrawContext context, float delta, CallbackInfo callback) {
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
        MinecraftClient client = MinecraftClient.getInstance();
        // The injection sits at the RETURN of the HUD render, which still runs while F1 hides the
        // HUD: without this the square and its label stayed on an otherwise empty screen and were
        // painted over the chat and the hotbar, because RETURN is the last thing that happens.
        if (client.options.hudHidden) {
            return;
        }
        int windowWidth = client.getWindow().getScaledWidth();
        int windowHeight = client.getWindow().getScaledHeight();
        HudIndicatorCorner corner = settings.getCorner();
        int margin = settings.getMargin();
        int size = settings.getSize();
        int left = corner.isRight() ? windowWidth - margin - size : margin;
        int top = corner.isBottom() ? windowHeight - margin - size : margin;
        // The drag offset comes from the settings screen, but the config file is plain text a
        // player can edit, so clamp the final position instead of trusting the stored range: the
        // indicator must never end up somewhere it cannot be dragged back from.
        left += settings.getOffsetX();
        top += settings.getOffsetY();
        left = Math.max(0, Math.min(windowWidth - size, left));
        top = Math.max(0, Math.min(windowHeight - size, top));
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
        TextRenderer previewFont = client.textRenderer;
        // The text renderer is null while resources reload and the HUD keeps drawing through that
        // window, so this frame has to skip the preview instead of measuring with nothing.
        if (outgoingPreview != null && previewFont != null) {
            TranslationRenderContext.pushTextInput();
            try {
                int previewCenterX = windowWidth / 2;
                int previewY = Math.max(0, windowHeight - 60);
                context.drawCenteredTextWithShadow(previewFont,
                        HudIndicatorLabels.preview(outgoingPreview), previewCenterX, previewY, 0xFFFFFFFF);
            } finally {
                TranslationRenderContext.popTextInput();
            }
        }

        // The label is drawn in both states: the target language and the provider are settings, so
        // they stay informative even while translation is off.
        HudIndicatorContent content = settings.getContent();
        if (content == HudIndicatorContent.DOT) {
            return;
        }
        String label = null;
        if (content == HudIndicatorContent.LANGUAGE) {
            label = quickSettings.getTargetLanguage();
        } else if (content == HudIndicatorContent.PROVIDER) {
            label = quickSettings.getProvider();
        } else if (content == HudIndicatorContent.ACTIVITY && TranslationActivity.isTranslating()) {
            label = HudIndicatorLabels.activity();
        }
        if (label == null || label.isEmpty()) {
            return;
        }
        TextRenderer font = client.textRenderer;
        if (font == null) {
            // Resource reloads null the text renderer while the HUD keeps drawing.
            return;
        }
        // This version hooks TextRenderer.getWidth and TextRenderer.drawInternal in order to
        // translate what they render, so measuring or drawing the label unguarded would feed the
        // indicator's own text back into the translation path.
        TranslationRenderContext.pushTextInput();
        try {
            int labelWidth = font.getWidth(label);
            // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
            // centred on a right-corner square would run off the screen edge. The square itself is
            // clamped to the window above, so the label has to be clamped too: a provider id is
            // wider than the square and ran off the opposite edge on a narrow window.
            int labelCenterX = corner.isRight()
                    ? left - 2 - labelWidth / 2
                    : right + 2 + labelWidth / 2;
            int minimumCentre = labelWidth / 2;
            int maximumCentre = Math.max(minimumCentre, windowWidth - labelWidth / 2);
            labelCenterX = Math.max(minimumCentre, Math.min(maximumCentre, labelCenterX));
            // y is the top of the text line, so centre it against the square by hand. A line is
            // font.fontHeight tall, not the hard-coded 8 this copy started with, and the square can
            // be smaller than one, in which case the label moves above the square instead of onto it.
            int labelY = size >= font.fontHeight
                    ? top + (size - font.fontHeight) / 2
                    : Math.max(0, top - font.fontHeight);
            context.drawCenteredTextWithShadow(
                    font, HudIndicatorLabels.label(label), labelCenterX, labelY, 0xFFFFFFFF);
        } finally {
            TranslationRenderContext.popTextInput();
        }
    }
}
