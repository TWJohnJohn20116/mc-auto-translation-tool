package org.universaltranslator.fabric.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.Window;
import net.minecraft.text.TranslatableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
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
 *
 * <p>1.14 exposes {@code MinecraftClient.window} as a public field; {@code getWindow()} only arrives
 * in 1.15, which is why this file and its 1.15 twin differ in exactly one line.
 */
@Mixin(InGameHud.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(float delta, CallbackInfo callback) {
        // Read the settings first: a disabled indicator draws nothing at all.
        HudIndicatorSettings settings = FabricTranslationRuntime.homeSettings().getHudIndicator();
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
                if (FabricTranslationRuntime.homeSettings().isEnabled()) {
                    return;
                }
                break;
            case ALWAYS:
            default:
                break;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        Window window = client.window;
        if (window == null) {
            return;
        }
        int screenWidth = window.getScaledWidth();
        int screenHeight = window.getScaledHeight();
        HudIndicatorCorner corner = settings.getCorner();
        int margin = settings.getMargin();
        int size = settings.getSize();
        int left = corner.isRight() ? screenWidth - margin - size : margin;
        int top = corner.isBottom() ? screenHeight - margin - size : margin;
        // The drag offset comes from the settings screen, but the config file is plain text a
        // player can edit, so clamp the final position instead of trusting the stored range: the
        // indicator must never end up somewhere it cannot be dragged back from.
        left += settings.getOffsetX();
        top += settings.getOffsetY();
        left = Math.max(0, Math.min(screenWidth - size, left));
        top = Math.max(0, Math.min(screenHeight - size, top));
        int right = left + size;
        int bottom = top + size;
        int borderColor = 0xFF000000;
        // One pixel of opaque black keeps the indicator readable against sky and bright terrain.
        DrawableHelper.fill(left - 1, top - 1, right + 1, top, borderColor);
        DrawableHelper.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        DrawableHelper.fill(left - 1, top, left, bottom, borderColor);
        DrawableHelper.fill(right, top, right + 1, bottom, borderColor);
        boolean translationOn = FabricTranslationRuntime.homeSettings().isEnabled();
        if (translationOn) {
            // Solid colour while translation is on: configurable, green by default.
            DrawableHelper.fill(left, top, right, bottom, settings.getColor().argb());
        } else {
            // A hollow red frame while translation is off, so the two states differ in shape as well
            // as in colour and remain distinguishable for players who cannot separate the two hues.
            int disabledColor = 0xFFFF5555;
            DrawableHelper.fill(left, top, right, top + 1, disabledColor);
            DrawableHelper.fill(left, bottom - 1, right, bottom, disabledColor);
            DrawableHelper.fill(left, top + 1, left + 1, bottom - 1, disabledColor);
            DrawableHelper.fill(right - 1, top + 1, right, bottom - 1, disabledColor);
        }
        // The outgoing chat translation as far as it has been generated. Drawn from the same
        // render path as the indicator and only while that translation is still running, so the
        // player sees the answer arrive instead of waiting for the whole line.
        String outgoingPreview = OutgoingTranslationPreview.text();
        if (outgoingPreview != null) {
            TranslationRenderContext.pushTextInput();
            try {
                int previewY = Math.max(0, screenHeight - 60);
                int previewX = screenWidth / 2 - client.textRenderer.getStringWidth(outgoingPreview) / 2;
                client.textRenderer.drawWithShadow(outgoingPreview, (float) previewX, (float) previewY, 0xFFFFFFFF);
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
            label = FabricTranslationRuntime.homeSettings().getTargetLanguage();
        } else if (content == HudIndicatorContent.PROVIDER) {
            label = FabricTranslationRuntime.homeSettings().getProvider();
        } else if (content == HudIndicatorContent.ACTIVITY
                && TranslationActivity.isTranslating()) {
            label = new TranslatableText(
                    "value.universal_translator.hud_content.activity").getString();
        }
        if (label == null || label.isEmpty()) {
            return;
        }
        TextRenderer font = client.textRenderer;
        if (font == null) {
            return;
        }
        int labelWidth = font.getStringWidth(label);
        // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
        // centred on a right-corner square would run off the screen edge.
        int labelLeft = corner.isRight() ? left - 2 - labelWidth : right + 2;
        // y is the top of the text line, so centre it against the square by hand.
        int labelY = top + (size - 8) / 2;
        // These versions hook TextRenderer.draw* to translate world text, and the indicator's own
        // label must not be fed back into that path: reuse the "this text is not translated" guard
        // the text-field widgets already rely on.
        TranslationRenderContext.pushTextInput();
        try {
            font.drawWithShadow(label, (float) labelLeft, (float) labelY, 0xFFFFFFFF);
        } finally {
            TranslationRenderContext.popTextInput();
        }
    }
}
