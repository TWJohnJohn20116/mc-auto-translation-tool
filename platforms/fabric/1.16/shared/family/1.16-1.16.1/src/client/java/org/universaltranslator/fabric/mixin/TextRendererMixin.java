package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.StringRenderable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

/**
 * Captures world-space text such as nameplates, holograms, signs and display entities.
 *
 * <p>Every hook is optional ({@code require = 0}): this tree covers 1.16 and 1.16.1, whose
 * {@code drawInternal} overloads differ in their trailing arguments, and the layer's
 * {@code defaultRequire} is 1 — a descriptor that stops matching would abort the launch instead of
 * leaving one surface untranslated. Each descriptor above is the one the mapped 1.16 artifact
 * exposes; when a neighbouring patch version changes the tail, the candidate is the same method
 * with the extra trailing {@code boolean} added or removed.
 */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    @ModifyVariable(
            method = "drawInternal(Ljava/lang/String;FFIZLnet/minecraft/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translatePreparedString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawInternal(Lnet/minecraft/text/StringRenderable;FFIZLnet/minecraft/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZII)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private StringRenderable universalTranslator$translatePreparedRenderable(StringRenderable text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"),
            argsOnly = true, require = 0)
    private String universalTranslator$translateMeasuredString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/StringRenderable;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private StringRenderable universalTranslator$translateMeasuredRenderable(StringRenderable text) {
        return RenderedTextBridge.translate(text);
    }
}
