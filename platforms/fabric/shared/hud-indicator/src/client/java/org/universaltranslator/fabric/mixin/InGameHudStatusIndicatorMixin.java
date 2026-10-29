package org.universaltranslator.fabric.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.math.MatrixStack;
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
 * <p>The window size comes from {@code MinecraftClient.getWindow()} rather than {@code InGameHud}'s
 * own scaled-width fields, which are private on these versions.
 *
 * <p>All state lives in locals on purpose: a mixin that declares extra fields or helper methods
 * risks colliding with members of the target class.
 *
 * <p>The label is looked up through {@code I18n.translate} rather than a {@code Text} factory
 * because the shared 1.16.2-1.19.2 source layer spans the 1.19 rename that removed
 * {@code TranslatableText}; {@code I18n.translate} is the one API present in every version this
 * file is compiled against.
 */
@Mixin(InGameHud.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;F)V", at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            MatrixStack matrices, float delta, CallbackInfo callback) {
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
        DrawableHelper.fill(matrices, left - 1, top - 1, right + 1, top, borderColor);
        DrawableHelper.fill(matrices, left - 1, bottom, right + 1, bottom + 1, borderColor);
        DrawableHelper.fill(matrices, left - 1, top, left, bottom, borderColor);
        DrawableHelper.fill(matrices, right, top, right + 1, bottom, borderColor);
        boolean translationOn = quickSettings.isEnabled();
        if (translationOn) {
            // Solid colour while translation is on: configurable, green by default.
            DrawableHelper.fill(matrices, left, top, right, bottom, settings.getColor().argb());
        } else {
            // A hollow red frame while translation is off, so the two states differ in shape as well
            // as in colour and remain distinguishable for players who cannot separate the two hues.
            int disabledColor = 0xFFFF5555;
            DrawableHelper.fill(matrices, left, top, right, top + 1, disabledColor);
            DrawableHelper.fill(matrices, left, bottom - 1, right, bottom, disabledColor);
            DrawableHelper.fill(matrices, left, top + 1, left + 1, bottom - 1, disabledColor);
            DrawableHelper.fill(matrices, right - 1, top + 1, right, bottom - 1, disabledColor);
        }
        // The outgoing chat translation as far as it has been generated. Drawn from the same
        // render path as the indicator and only while that translation is still running, so the
        // player sees the answer arrive instead of waiting for the whole line.
        //
        // Both self-drawn strings share one text-input latch: these versions hook
        // TextRenderer.getWidth and TextRenderer.drawInternal in order to translate what they
        // render, so measuring or drawing the indicator's own text unguarded would feed it back into
        // the translation path. One push and one pop per frame instead of two.
        String outgoingPreview = OutgoingTranslationPreview.text();
        HudIndicatorContent content = settings.getContent();
        TextRenderer font = client.textRenderer;
        TranslationRenderContext.pushTextInput();
        try {
            if (outgoingPreview != null) {
                int previewY = Math.max(0, windowHeight - 60);
                int previewX = windowWidth / 2 - font.getWidth(outgoingPreview) / 2;
                font.drawWithShadow(matrices, outgoingPreview, (float) previewX, (float) previewY, 0xFFFFFFFF);
            }

            // The label is drawn in both states: the target language and the provider are settings,
            // so they stay informative even while translation is off.
            if (content == HudIndicatorContent.DOT) {
                return;
            }
            String label = null;
            if (content == HudIndicatorContent.LANGUAGE) {
                label = quickSettings.getTargetLanguage();
            } else if (content == HudIndicatorContent.PROVIDER) {
                label = quickSettings.getProvider();
            } else if (content == HudIndicatorContent.ACTIVITY && TranslationActivity.isTranslating()) {
                label = I18n.translate("value.universal_translator.hud_content.activity");
            }
            if (label == null || label.isEmpty()) {
                return;
            }
            int labelWidth = font.getWidth(label);
            // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
            // centred on a right-corner square would run off the screen edge.
            int labelLeft = corner.isRight() ? left - 2 - labelWidth : right + 2;
            // The square is clamped to the window, the label was not: a provider id is wider than
            // the square, so on a narrow window the right-corner label ran off the left edge and the
            // left-corner one off the right. Clamped here instead, and cut to the room that is left.
            labelLeft = Math.max(0, Math.min(windowWidth - labelWidth, labelLeft));
            // y is the top of the text line, so centre it against the square by hand.
            int labelY = top + (size - 8) / 2;
            font.drawWithShadow(matrices, label, (float) labelLeft, (float) labelY, 0xFFFFFFFF);
        } finally {
            TranslationRenderContext.popTextInput();
        }
    }
}
