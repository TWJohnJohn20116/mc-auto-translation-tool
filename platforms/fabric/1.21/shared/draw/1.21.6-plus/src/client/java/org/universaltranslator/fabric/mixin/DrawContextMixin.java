package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Translates the text a screen hands to the graphics context.
 *
 * <p>Every hook is optional ({@code require = 0}). The bridge already answers "the original" when
 * translation cannot run, so a descriptor that drifts between patch versions must cost that one
 * surface instead of aborting the launch — {@code defaultRequire} is 1 in this tree.
 */
@Mixin(DrawContext.class)
abstract class DrawContextMixin {
    // One flag per require = 0 hook: only a hook that actually fired can separate a descriptor that
    // moved between patch versions from a version that never had that entry point.
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
            method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private List<Text> universalTranslator$translateTooltipLines(List<Text> lines) {
        RenderedTextBridge.surface("draw-context-tooltip-1.21.6", universalTranslator$tooltipSurface);
        return RenderedTextBridge.translateTooltip(lines);
    }

    @ModifyVariable(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateString(String text) {
        RenderedTextBridge.surface("draw-context-string-1.21.6", universalTranslator$stringSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Text universalTranslator$translateText(Text text) {
        RenderedTextBridge.surface("draw-context-text-1.21.6", universalTranslator$textSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateOrderedText(OrderedText text) {
        RenderedTextBridge.surface("draw-context-ordered-1.21.6",
                universalTranslator$orderedTextSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateCenteredString(String text) {
        RenderedTextBridge.surface("draw-context-centered-string-1.21.6",
                universalTranslator$centeredStringSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Text universalTranslator$translateCenteredText(Text text) {
        RenderedTextBridge.surface("draw-context-centered-text-1.21.6",
                universalTranslator$centeredTextSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateCenteredOrderedText(OrderedText text) {
        RenderedTextBridge.surface("draw-context-centered-ordered-1.21.6",
                universalTranslator$centeredOrderedSurface);
        return RenderedTextBridge.translate(text);
    }
}
