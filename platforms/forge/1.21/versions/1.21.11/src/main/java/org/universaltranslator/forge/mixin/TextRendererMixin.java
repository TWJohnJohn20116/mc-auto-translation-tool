package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.forge.RenderedTextBridge;

/** Captures world-space text such as nameplates, holograms, signs and display entities. */
@Mixin(value = Font.class, remap = false)
abstract class TextRendererMixin {
    @ModifyVariable(
            method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translatePreparedString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence universalTranslator$translatePreparedFormattedCharSequence(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateMeasuredString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "width(Lnet/minecraft/network/chat/FormattedText;)I",
            at = @At("HEAD"), argsOnly = true)
    private FormattedText universalTranslator$translateMeasuredText(FormattedText text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "width(Lnet/minecraft/util/FormattedCharSequence;)I",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence universalTranslator$translateMeasuredFormattedCharSequence(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }
}
