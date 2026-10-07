package org.universaltranslator.fabric.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
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
 * This variant targets the {@code Hud} HUD class used by Minecraft 26.3. All state lives in locals
 * on purpose: a mixin that declares extra fields or helper methods risks colliding with members of
 * the target class.
 */
@Mixin(Hud.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
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
        int left = corner.isRight() ? graphics.guiWidth() - margin - size : margin;
        int top = corner.isBottom() ? graphics.guiHeight() - margin - size : margin;
        int right = left + size;
        int bottom = top + size;
        int borderColor = 0xFF000000;
        // One pixel of opaque black keeps the indicator readable against sky and bright terrain.
        graphics.fill(left - 1, top - 1, right + 1, top, borderColor);
        graphics.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        graphics.fill(left - 1, top, left, bottom, borderColor);
        graphics.fill(right, top, right + 1, bottom, borderColor);
        boolean translationOn = FabricTranslationRuntime.homeSettings().isEnabled();
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
                label = net.minecraft.network.chat.Component.translatable(
                        "value.universal_translator.hud_content.activity").getString();
            }
            if (label != null && !label.isEmpty()) {
                net.minecraft.client.gui.Font font =
                        net.minecraft.client.Minecraft.getInstance().font;
                net.minecraft.network.chat.Component text =
                        net.minecraft.network.chat.Component.literal(label);
                int labelWidth = font.width(text);
                // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
                // centred on a right-corner square would run off the screen edge.
                int labelCenterX = corner.isRight()
                        ? left - 2 - labelWidth / 2
                        : right + 2 + labelWidth / 2;
                // y is the top of the text line, so centre it against the square by hand.
                int labelY = top + (size - 8) / 2;
                graphics.centeredText(font, text, labelCenterX, labelY, 0xFFFFFFFF);
            }
        }
    }
}
