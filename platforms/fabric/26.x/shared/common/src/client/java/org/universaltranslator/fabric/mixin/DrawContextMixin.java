package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

import java.util.List;

/**
 * Translates the text a screen hands to the graphics extractor.
 *
 * <p>Every hook is optional ({@code require = 0}). The bridge already answers "the original" when
 * translation cannot run, so a descriptor that drifts between patch versions must cost that one
 * surface instead of aborting the launch — {@code defaultRequire} is 1 in this tree.
 */
@Mixin(GuiGraphicsExtractor.class)
abstract class DrawContextMixin {
    @ModifyVariable(
            method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private List<Component> universalTranslator$translateTooltipLines(List<Component> lines) {
        return RenderedTextBridge.translateTooltip(lines);
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component universalTranslator$translateText(Component text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedCharSequence universalTranslator$translateOrderedText(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "centeredText(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateCenteredString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component universalTranslator$translateCenteredText(Component text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedCharSequence universalTranslator$translateCenteredOrderedText(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }
}
