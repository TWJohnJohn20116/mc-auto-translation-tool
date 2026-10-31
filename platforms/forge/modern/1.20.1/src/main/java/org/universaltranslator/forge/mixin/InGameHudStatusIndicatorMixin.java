package org.universaltranslator.forge.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudLabelCache;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.OutgoingTranslationPreview;
import org.universaltranslator.core.TranslationActivity;
import org.universaltranslator.forge.ForgeMixinDiagnostics;
import org.universaltranslator.forge.ForgeTranslationRuntime;
import org.universaltranslator.forge.HudLabels;
import org.universaltranslator.forge.TranslationRenderContext;

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
    // Optional: pure HUD decoration, so a missing target must not block the launch. The first run
    // is reported to the debug log so a skipped hook is not mistaken for "translation is off".
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At("RETURN"),
            require = 0)
    private void universalTranslator$renderStatusIndicator(
            GuiGraphics graphics, float delta, CallbackInfo callback) {
        ForgeMixinDiagnostics.optionalHookActive(ForgeMixinDiagnostics.HUD_INDICATOR, "hud-indicator");
        // Read the settings first: a disabled indicator draws nothing at all.
        // One read per frame: the runtime publishes a finished snapshot, and this method needs
        // four different fields off the same one.
        HomeQuickSettingsState quickSettings = ForgeTranslationRuntime.homeSettings();
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
            TranslationRenderContext.pushTextInput();
            // The preview only changes while a translation is landing, so the component is built once
            // per distinct value instead of once per frame.
            long previewNow = System.currentTimeMillis();
            Object cachedPreview = HudLabels.preview(outgoingPreview, previewNow);
            Component preview;
            if (cachedPreview instanceof Component) {
                preview = (Component) cachedPreview;
            } else {
                preview = Component.literal(outgoingPreview);
                HudLabels.rememberPreview(outgoingPreview, preview, previewNow);
            }
            graphics.drawCenteredString(Minecraft.getInstance().font, preview, previewCenterX, previewY, 0xFFFFFFFF);
            TranslationRenderContext.popTextInput();
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
                // Resolved once per second instead of once per frame: the language-table lookup and
                // the fresh String it returns were the whole per-frame cost of this branch.
                long now = System.currentTimeMillis();
                if (HudLabels.activityExpired(now)) {
                    HudLabels.rememberActivity(Component.translatable(
                            HudLabelCache.ACTIVITY_KEY).getString(), now);
                }
                label = HudLabels.activity();
            }
            if (label != null && !label.isEmpty()) {
                net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
                long now = System.currentTimeMillis();
                Object cachedLabel = HudLabels.label(label, now);
                Component text;
                if (cachedLabel instanceof Component) {
                    text = (Component) cachedLabel;
                } else {
                    text = Component.literal(label);
                    HudLabels.rememberLabel(label, text, now);
                }
                TranslationRenderContext.pushTextInput();
                // A glyph walk per frame, for a string that only changes when the language or the font
                // does: both are covered by the one-second slot.
                int labelWidth = HudLabels.width(label, now);
                if (labelWidth < 0) {
                    labelWidth = font.width(text);
                    HudLabels.rememberWidth(label, labelWidth, now);
                }
                // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
                // centred on a right-corner square would run off the screen edge.
                int labelCenterX = corner.isRight()
                        ? left - 2 - labelWidth / 2
                        : right + 2 + labelWidth / 2;
                // y is the top of the text line, so centre it against the square by hand.
                int labelY = top + (size - 8) / 2;
                graphics.drawCenteredString(font, text, labelCenterX, labelY, 0xFFFFFFFF);
                TranslationRenderContext.popTextInput();
            }
        }
    }
}
