package org.universaltranslator.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.TextRenderer;

import net.minecraft.locale.I18n;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.OutgoingTranslationPreview;
import org.universaltranslator.core.TranslationActivity;
import org.universaltranslator.fabric.FabricTranslationRuntime;
import org.universaltranslator.fabric.TranslationRenderContext;
/**
 * Draws the translation status indicator on the HUD.
 *
 * <p>Separate from the mixins so the body exists once: the 1.6-1.8 layer has to hook two render
 * entry points (1.6.1-1.7.10 use render(FZII)V, 1.8 uses render(F)V) and both must draw exactly
 * the same thing. Nothing here touches mixin members, so it is an ordinary class.
 */
final class HudIndicatorOverlay {
    private HudIndicatorOverlay() {
    }

    static void render(int windowWidth, int windowHeight) {
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
        // The caller supplies the GUI-scaled size. No version in this bundle lets a mixin reach the
        // Window (the field exists but is not visible), so each injection point passes what it
        // already has: render(FZII)V carries width/height, renderHotbar carries the Window.
        Minecraft client = Minecraft.getInstance();
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
        GuiElement.fill(left - 1, top - 1, right + 1, top, borderColor);
        GuiElement.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        GuiElement.fill(left - 1, top, left, bottom, borderColor);
        GuiElement.fill(right, top, right + 1, bottom, borderColor);
        boolean translationOn = FabricTranslationRuntime.homeSettings().isEnabled();
        if (translationOn) {
            // Solid colour while translation is on: configurable, green by default.
            GuiElement.fill(left, top, right, bottom, settings.getColor().argb());
        } else {
            // A hollow red frame while translation is off, so the two states differ in shape as well
            // as in colour and remain distinguishable for players who cannot separate the two hues.
            int disabledColor = 0xFFFF5555;
            GuiElement.fill(left, top, right, top + 1, disabledColor);
            GuiElement.fill(left, bottom - 1, right, bottom, disabledColor);
            GuiElement.fill(left, top + 1, left + 1, bottom - 1, disabledColor);
            GuiElement.fill(right - 1, top + 1, right, bottom - 1, disabledColor);
        }
        // The outgoing chat translation as far as it has been generated. Drawn from the same
        // render path as the indicator and only while that translation is still running, so the
        // player sees the answer arrive instead of waiting for the whole line.
        String outgoingPreview = OutgoingTranslationPreview.text();
        if (outgoingPreview != null) {
            TranslationRenderContext.pushTextInput();
            try {
                int previewY = Math.max(0, windowHeight - 60);
                int previewX = windowWidth / 2 - client.textRenderer.getWidth(outgoingPreview) / 2;
                client.textRenderer.drawWithShadow(outgoingPreview, previewX, previewY, 0xFFFFFFFF);
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
        } else if (content == HudIndicatorContent.ACTIVITY && TranslationActivity.isTranslating()) {
            label = I18n.translate("value.universal_translator.hud_content.activity");
        }
        if (label == null || label.isEmpty()) {
            return;
        }
        TextRenderer font = client.textRenderer;
        // This version hooks TextRenderer.getWidth and TextRenderer.draw in order to translate what
        // it renders, so measuring or drawing the label unguarded would feed the indicator's own
        // text back into the translation path.
        TranslationRenderContext.pushTextInput();
        try {
            int labelWidth = font.getWidth(label);
            // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
            // centred on a right-corner square would run off the screen edge.
            int labelLeft = corner.isRight() ? left - 2 - labelWidth : right + 2;
            // y is the top of the text line, so centre it against the square by hand.
            int labelY = top + (size - 8) / 2;
            font.drawWithShadow(label, labelLeft, labelY, 0xFFFFFFFF);
        } finally {
            TranslationRenderContext.popTextInput();
        }
    }
}