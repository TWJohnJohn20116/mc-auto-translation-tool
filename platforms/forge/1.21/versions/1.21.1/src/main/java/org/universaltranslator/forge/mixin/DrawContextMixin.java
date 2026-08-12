package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.forge.RenderedTextBridge;

@Mixin(value = GuiGraphics.class, remap = false)
abstract class DrawContextMixin {
    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I",
            at = @At("HEAD"), argsOnly = true)
    private Component universalTranslator$translateText(Component text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence universalTranslator$translateFormattedCharSequence(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateCenteredString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true)
    private Component universalTranslator$translateCenteredText(Component text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence universalTranslator$translateCenteredFormattedCharSequence(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }
}
