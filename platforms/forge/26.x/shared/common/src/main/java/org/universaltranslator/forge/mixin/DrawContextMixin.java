package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.forge.RenderedTextBridge;

import java.util.List;

@Mixin(remap = false, value = GuiGraphicsExtractor.class)
abstract class DrawContextMixin {
    @ModifyVariable(
            method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private List<Component> universalTranslator$translateTooltipLines(List<Component> lines) {
        return RenderedTextBridge.translateTooltip(lines);
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private Component universalTranslator$translateText(Component text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence universalTranslator$translateOrderedText(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "centeredText(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateCenteredString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true)
    private Component universalTranslator$translateCenteredText(Component text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence universalTranslator$translateCenteredOrderedText(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }
}
