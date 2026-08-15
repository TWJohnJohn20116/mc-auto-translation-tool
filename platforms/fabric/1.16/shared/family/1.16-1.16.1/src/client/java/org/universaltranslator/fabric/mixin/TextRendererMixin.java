package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.StringRenderable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

/** Captures world-space text such as nameplates, holograms, signs and display entities. */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    @ModifyVariable(
            method = "drawInternal(Ljava/lang/String;FFIZLnet/minecraft/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZIIZ)I",
            at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translatePreparedString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawInternal(Lnet/minecraft/text/StringRenderable;FFIZLnet/minecraft/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZII)I",
            at = @At("HEAD"), argsOnly = true)
    private StringRenderable universalTranslator$translatePreparedRenderable(StringRenderable text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateMeasuredString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/StringRenderable;)I",
            at = @At("HEAD"), argsOnly = true)
    private StringRenderable universalTranslator$translateMeasuredRenderable(StringRenderable text) {
        return RenderedTextBridge.translate(text);
    }
}
