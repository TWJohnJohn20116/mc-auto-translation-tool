package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Translates the text a screen hands to the graphics extractor.
 *
 * <p>Every hook is optional ({@code require = 0}). The bridge already answers "the original" when
 * translation cannot run, so a descriptor that drifts between patch versions must cost that one
 * surface instead of aborting the launch — {@code defaultRequire} is 1 in this tree.
 */
@Mixin(GuiGraphicsExtractor.class)
abstract class DrawContextMixin {
    // One flag per require = 0 hook: only a hook that actually fired can separate a descriptor that
    // drifted between patch versions from a version that never had that entry point.
    private static final AtomicBoolean universalTranslator$tooltipSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$stringSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$textSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$orderedTextSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$centeredStringSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$centeredTextSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$centeredOrderedSurface =
            new AtomicBoolean();

    @ModifyVariable(
            method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private List<Component> universalTranslator$translateTooltipLines(List<Component> lines) {
        RenderedTextBridge.surface("draw-context-tooltip-26.x", universalTranslator$tooltipSurface);
        return RenderedTextBridge.translateTooltip(lines);
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateString(String text) {
        RenderedTextBridge.surface("draw-context-string-26.x", universalTranslator$stringSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component universalTranslator$translateText(Component text) {
        RenderedTextBridge.surface("draw-context-text-26.x", universalTranslator$textSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedCharSequence universalTranslator$translateOrderedText(FormattedCharSequence text) {
        RenderedTextBridge.surface("draw-context-ordered-26.x",
                universalTranslator$orderedTextSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "centeredText(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateCenteredString(String text) {
        RenderedTextBridge.surface("draw-context-centered-string-26.x",
                universalTranslator$centeredStringSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component universalTranslator$translateCenteredText(Component text) {
        RenderedTextBridge.surface("draw-context-centered-text-26.x",
                universalTranslator$centeredTextSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedCharSequence universalTranslator$translateCenteredOrderedText(FormattedCharSequence text) {
        RenderedTextBridge.surface("draw-context-centered-ordered-26.x",
                universalTranslator$centeredOrderedSurface);
        return RenderedTextBridge.translate(text);
    }
}
