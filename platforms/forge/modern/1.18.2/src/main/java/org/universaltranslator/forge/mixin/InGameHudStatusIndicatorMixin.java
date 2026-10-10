package org.universaltranslator.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.OutgoingTranslationPreview;
import org.universaltranslator.core.TranslationActivity;
import org.universaltranslator.forge.ForgeTranslationRuntime;

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
@Mixin(Gui.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            PoseStack pose, float delta, CallbackInfo callback) {
        // Read the settings first: a disabled indicator draws nothing at all.
        HudIndicatorSettings settings = ForgeTranslationRuntime.homeSettings().getHudIndicator();
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
                if (ForgeTranslationRuntime.homeSettings().isEnabled()) {
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
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
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
        Screen.fill(pose, left - 1, top - 1, right + 1, top, borderColor);
        Screen.fill(pose, left - 1, bottom, right + 1, bottom + 1, borderColor);
        Screen.fill(pose, left - 1, top, left, bottom, borderColor);
        Screen.fill(pose, right, top, right + 1, bottom, borderColor);
        boolean translationOn = ForgeTranslationRuntime.homeSettings().isEnabled();
        if (translationOn) {
            // Solid colour while translation is on: configurable, green by default.
            Screen.fill(pose, left, top, right, bottom, settings.getColor().argb());
        } else {
            // A hollow red frame while translation is off, so the two states differ in shape as well
            // as in colour and remain distinguishable for players who cannot separate the two hues.
            int disabledColor = 0xFFFF5555;
            Screen.fill(pose, left, top, right, top + 1, disabledColor);
            Screen.fill(pose, left, bottom - 1, right, bottom, disabledColor);
            Screen.fill(pose, left, top + 1, left + 1, bottom - 1, disabledColor);
            Screen.fill(pose, right - 1, top + 1, right, bottom - 1, disabledColor);
        }
        // The outgoing chat translation as far as it has been generated. Drawn from the same
        // render path as the indicator and only while that translation is still running, so the
        // player sees the answer arrive instead of waiting for the whole line.
        String outgoingPreview = OutgoingTranslationPreview.text();
        if (outgoingPreview != null) {
            int previewCenterX = screenWidth / 2;
            int previewY = Math.max(0, screenHeight - 60);
            Screen.drawCenteredString(pose, Minecraft.getInstance().font, new TextComponent(outgoingPreview), previewCenterX, previewY, 0xFFFFFFFF);
        }

        // The label is drawn in both states: the target language and the provider are settings, so
        // they stay informative even while translation is off.
        HudIndicatorContent content = settings.getContent();
        if (content != HudIndicatorContent.DOT) {
            String label = null;
            if (content == HudIndicatorContent.LANGUAGE) {
                label = ForgeTranslationRuntime.homeSettings().getTargetLanguage();
            } else if (content == HudIndicatorContent.PROVIDER) {
                label = ForgeTranslationRuntime.homeSettings().getProvider();
            } else if (content == HudIndicatorContent.ACTIVITY
                    && TranslationActivity.isTranslating()) {
                // 1.18.2 predates Component.translatable; the screens on this version all use the
                // concrete TranslatableComponent/TextComponent types, so follow that here too.
                label = new TranslatableComponent(
                        "value.universal_translator.hud_content.activity").getString();
            }
            if (label != null && !label.isEmpty()) {
                Font font = Minecraft.getInstance().font;
                Component text = new TextComponent(label);
                int labelWidth = font.width(text);
                // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
                // centred on a right-corner square would run off the screen edge.
                int labelCenterX = corner.isRight()
                        ? left - 2 - labelWidth / 2
                        : right + 2 + labelWidth / 2;
                // y is the top of the text line, so centre it against the square by hand.
                int labelY = top + (size - 8) / 2;
                Screen.drawCenteredString(pose, font, text, labelCenterX, labelY, 0xFFFFFFFF);
            }
        }
    }
}
