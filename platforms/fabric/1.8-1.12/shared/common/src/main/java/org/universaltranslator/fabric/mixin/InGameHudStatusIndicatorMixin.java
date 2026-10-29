package org.universaltranslator.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.Window;
import net.minecraft.client.resource.language.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
 * <p>This is the 1.8.2-1.12.2 twin of the Ornithe 1.13/1.13.1 mixin: this bundle compiles one source
 * set against every version it supports, and the ploceus names for the HUD, the render entry point
 * and the drawing helpers are the same on all of them. Two details do differ and are the reason this
 * file cannot be shared with the 1.13 layer:
 *
 * <ul>
 *   <li>the window lives in {@code net.minecraft.client.render.Window}, not in
 *       {@code net.minecraft.client.render.platform.Window};
 *   <li>{@code getScaledWidth()}/{@code getScaledHeight()} return {@code double} here, so the
 *       results are narrowed with a cast.
 * </ul>
 *
 * <p>Everything it draws comes from {@code HudIndicatorSettings}: the corner, size, margin, colour,
 * what appears inside the square, and when it is drawn at all.
 *
 * <p>State is kept in local variables except for the two {@code @Unique} fields that carry the HUD
 * size from the one call that receives the {@code Window} to the end-of-frame draw; {@code @Unique}
 * makes mixin rename them if the target class ever grows a member with the same name.
 */
@Mixin(GameGui.class)
abstract class InGameHudStatusIndicatorMixin {
    /**
     * The scaled HUD size, cached from the one call that hands out the {@code Window}.
     *
     * <p>Kept in {@code @Unique} fields because the size can only be read where the window itself is
     * available, while the drawing has to happen at the end of the HUD frame (see below).
     */
    @Unique
    private int universalTranslator$windowWidth;
    @Unique
    private int universalTranslator$windowHeight;
    /**
     * True once the HUD-tail injection has run at least once, which means the fallback draw below
     * would only paint the same pixels a second time.
     */
    @Unique
    private boolean universalTranslator$hudTailActive;

    /**
     * Paints the indicator at the end of the HUD frame.
     *
     * <p>This is the injection point that decides what the player sees. {@code renderHotbar} runs
     * before vanilla draws the chat log background, the item name and the potion effects, so an
     * indicator drawn there was painted over by the chat log whenever the corner was set to the
     * bottom half — while the settings screen's drag preview promised what-you-see-is-what-you-get.
     * 1.13 already injected at the HUD tail; this tree was the odd one out.
     */
    @Inject(method = {"render(F)V", "renderGameOverlay(F)V"}, at = @At("RETURN"), require = 0)
    private void universalTranslator$drawAfterHud(float delta, CallbackInfo callback) {
        universalTranslator$hudTailActive = true;
        universalTranslator$drawIndicator();
    }

    /**
     * Caches the HUD size, and draws only while the HUD tail is not reached.
     *
     * <p>The {@code Window} only arrives as a parameter of {@code renderHotbar}, and reading it from
     * {@code Minecraft} is not portable across 1.8.2-1.12.2 (the member is not visible to a mixin
     * outside its package). Drawing here as well keeps the indicator visible if the HUD-tail method
     * above is named differently in one of the mappings this bundle covers — but once that tail has
     * drawn even once, this pass would repeat the whole label: two shadow passes over the same
     * pixels read as a bolder, darker label than every other version draws.
     */
    @Inject(method = "renderHotbar(Lnet/minecraft/client/render/Window;F)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$renderStatusIndicator(Window window, float delta, CallbackInfo callback) {
        // The Window arrives as a parameter of renderHotbar, which every version this bundle is
        // compiled for (1.8.2-1.12.2) calls with it. Reading the window from Minecraft instead is
        // not portable: the member is not visible to a mixin outside its package, and the Window
        // constructor takes different arguments across these versions.
        if (window != null) {
            universalTranslator$windowWidth = (int) window.getScaledWidth();
            universalTranslator$windowHeight = (int) window.getScaledHeight();
        }
        if (!universalTranslator$hudTailActive) {
            universalTranslator$drawIndicator();
        }
    }

    @Unique
    private void universalTranslator$drawIndicator() {
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
        Minecraft client = Minecraft.getInstance();
        TextRenderer font = client.textRenderer;
        if (font == null) {
            // The renderer is null while a resource reload swaps it, so both the preview and the
            // label below would NPE out of the render thread; the check has to come first.
            return;
        }
        int windowWidth = universalTranslator$windowWidth;
        int windowHeight = universalTranslator$windowHeight;
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
        GuiElement.fill(left - 1, top - 1, right + 1, top, borderColor);
        GuiElement.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        GuiElement.fill(left - 1, top, left, bottom, borderColor);
        GuiElement.fill(right, top, right + 1, bottom, borderColor);
        boolean translationOn = quickSettings.isEnabled();
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
                int previewX = windowWidth / 2 - font.getWidth(outgoingPreview) / 2;
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
        // This version hooks TextRenderer.getWidth and TextRenderer.draw in order to translate what
        // it renders, so measuring or drawing the label unguarded would feed the indicator's own
        // text back into the translation path.
        TranslationRenderContext.pushTextInput();
        try {
            int labelWidth = font.getWidth(label);
            // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
            // centred on a right-corner square would run off the screen edge. Clamped afterwards,
            // because the square can sit against either edge.
            int labelLeft = corner.isRight() ? left - 2 - labelWidth : right + 2;
            labelLeft = Math.max(2, Math.min(windowWidth - labelWidth - 2, labelLeft));
            // y is the top of the text line, so centre it against the square by hand.
            int labelY = top + (size - 8) / 2;
            font.drawWithShadow(label, (float) labelLeft, (float) labelY, 0xFFFFFFFF);
        } finally {
            TranslationRenderContext.popTextInput();
        }
    }
}
