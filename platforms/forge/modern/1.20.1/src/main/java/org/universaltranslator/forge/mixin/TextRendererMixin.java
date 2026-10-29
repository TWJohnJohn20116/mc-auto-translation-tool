package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.forge.RenderedTextBridge;

/**
 * Captures world-space text such as nameplates, holograms, signs and display entities.
 *
 * <p>Every hook here is load-bearing for the translation pipeline: the {@code drawInternal}
 * variants are the single choke point every string reaches (including the ones
 * {@code GuiGraphics.drawString} forwards), and the {@code width} variants keep measuring the
 * translated text so centred and wrapped lines do not drift. The injector config sets
 * {@code defaultRequire: 0}, so each one states {@code require = 1} explicitly: a renamed or
 * removed target must fail the launch instead of silently leaving the client untranslated.
 */
@Mixin(Font.class)
abstract class TextRendererMixin {
    @ModifyVariable(
            method = "drawInternal(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;IIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 1)
    private String universalTranslator$translatePreparedString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawInternal(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
            at = @At("HEAD"), argsOnly = true, require = 1)
    private FormattedCharSequence universalTranslator$translatePreparedFormattedCharSequence(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true,
            require = 1)
    private String universalTranslator$translateMeasuredString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "width(Lnet/minecraft/network/chat/FormattedText;)I",
            at = @At("HEAD"), argsOnly = true, require = 1)
    private FormattedText universalTranslator$translateMeasuredText(FormattedText text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "width(Lnet/minecraft/util/FormattedCharSequence;)I",
            at = @At("HEAD"), argsOnly = true, require = 1)
    private FormattedCharSequence universalTranslator$translateMeasuredFormattedCharSequence(FormattedCharSequence text) {
        return RenderedTextBridge.translate(text);
    }
}
