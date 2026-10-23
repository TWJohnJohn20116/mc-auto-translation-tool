package org.universaltranslator.fabric.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.resource.language.I18n;
import org.lwjgl.glfw.GLFW;
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
 * Paints a small translation status indicator on the 1.13.2 HUD.
 *
 * <p>This is the legacy-yarn twin of the Ornithe mixin of the same name. The two mapping sets
 * disagree on every name this file needs: the HUD is {@code InGameHud} here and {@code GameGui}
 * there, {@code TextRenderer} lives in {@code client.font} here and {@code client.render} there,
 * and 1.13.2's yarn mappings have <em>no</b> {@code Window} class at all — the Ornithe mixin reads
 * the size from {@code Minecraft.window}, which simply does not exist on this mapping set.
 *
 * <p>The scaled size is therefore derived the same way vanilla's {@code Window#getScaledWidth}
 * derives it: framebuffer pixels divided by the configured GUI scale, with {@code guiScale == 0}
 * ("auto") resolved to the largest whole scale that still fits a 320x240 layout. Reading GLFW
 * directly avoids depending on an unmapped class, and this runs once per HUD frame, not per widget.
 *
 * <p>Everything else — corners, margin, size, drag offset, colours, content, visibility — comes
 * from {@code HudIndicatorSettings} and behaves exactly like the other seven platform families.
 *
 * <p>All state lives in locals on purpose: a mixin that declares extra fields or helper methods
 * risks colliding with members of the target class.
 */
@Mixin(InGameHud.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(method = "render(F)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$renderStatusIndicator(float delta, CallbackInfo callback) {
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
        if (client == null || client.options == null) {
            return;
        }
        int[] framebufferWidth = new int[1];
        int[] framebufferHeight = new int[1];
        long handle = GLFW.glfwGetCurrentContext();
        if (handle == 0L) {
            return;
        }
        GLFW.glfwGetFramebufferSize(handle, framebufferWidth, framebufferHeight);
        int guiScale = client.options.guiScale;
        int scale = guiScale > 0
                ? guiScale
                : Math.max(1, Math.min(framebufferWidth[0] / 320, framebufferHeight[0] / 240));
        int windowWidth = framebufferWidth[0] / scale;
        int windowHeight = framebufferHeight[0] / scale;
        if (windowWidth <= 0 || windowHeight <= 0) {
            return;
        }
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
        DrawableHelper.fill(left - 1, top - 1, right + 1, top, borderColor);
        DrawableHelper.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        DrawableHelper.fill(left - 1, top, left, bottom, borderColor);
        DrawableHelper.fill(right, top, right + 1, bottom, borderColor);
        boolean translationOn = quickSettings.isEnabled();
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
        TextRenderer font = client.textRenderer;
        if (font == null) {
            return;
        }
        // The outgoing chat translation as far as it has been generated. Drawn from the same
        // render path as the indicator and only while that translation is still running, so the
        // player sees the answer arrive instead of waiting for the whole line.
        String outgoingPreview = OutgoingTranslationPreview.text();
        if (outgoingPreview != null) {
            TranslationRenderContext.pushTextInput();
            try {
                int previewY = Math.max(0, windowHeight - 60);
                int previewX = windowWidth / 2 - font.getStringWidth(outgoingPreview) / 2;
                font.drawWithShadow(outgoingPreview, (float) previewX, (float) previewY, 0xFFFFFFFF);
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
            label = I18n.translate("value.universal_translator.hud_content.activity");
        }
        if (label == null || label.isEmpty()) {
            return;
        }
        // This version hooks TextRenderer.getStringWidth and drawWithShadow in order to translate
        // what it renders, so measuring or drawing the label unguarded would feed the indicator's
        // own text back into the translation path.
        TranslationRenderContext.pushTextInput();
        try {
            int labelWidth = font.getStringWidth(label);
            // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
            // centred on a right-corner square would run off the screen edge.
            int labelLeft = corner.isRight() ? left - 2 - labelWidth : right + 2;
            // y is the top of the text line, so centre it against the square by hand.
            int labelY = top + (size - 8) / 2;
            font.drawWithShadow(label, (float) labelLeft, (float) labelY, 0xFFFFFFFF);
        } finally {
            TranslationRenderContext.popTextInput();
        }
    }
}
