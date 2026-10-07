package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.TranslationActivity;
import org.universaltranslator.fabric.FabricTranslationRuntime;

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
        boolean translationOn = FabricTranslationRuntime.homeSettings().isEnabled();
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
        // The label is drawn in both states: the target language and the provider are settings, so
        // they stay informative even while translation is off.
        HudIndicatorContent content = settings.getContent();
        if (content != HudIndicatorContent.DOT) {
            String label = null;
            if (content == HudIndicatorContent.LANGUAGE) {
                label = FabricTranslationRuntime.homeSettings().getTargetLanguage();
            } else if (content == HudIndicatorContent.PROVIDER) {
                label = FabricTranslationRuntime.homeSettings().getProvider();
            } else if (content == HudIndicatorContent.ACTIVITY
                    && TranslationActivity.isTranslating()) {
                label = net.minecraft.text.Text.translatable(
                        "value.universal_translator.hud_content.activity").getString();
            }
            if (label != null && !label.isEmpty()) {
                net.minecraft.client.font.TextRenderer font =
                        net.minecraft.client.MinecraftClient.getInstance().textRenderer;
                net.minecraft.text.Text text = net.minecraft.text.Text.literal(label);
                int labelWidth = font.getWidth(text);
                // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
                // centred on a right-corner square would run off the screen edge.
                int labelCenterX = corner.isRight()
                        ? left - 2 - labelWidth / 2
                        : right + 2 + labelWidth / 2;
                // y is the top of the text line, so centre it against the square by hand.
                int labelY = top + (size - 8) / 2;
                context.drawCenteredTextWithShadow(font, text, labelCenterX, labelY, 0xFFFFFFFF);
            }
        }
    }
}
