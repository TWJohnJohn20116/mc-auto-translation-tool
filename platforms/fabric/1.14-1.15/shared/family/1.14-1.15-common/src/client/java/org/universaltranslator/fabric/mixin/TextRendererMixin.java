package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

/** Captures world-space text such as nameplates, holograms, signs and display entities. */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    @ModifyVariable(method = "draw(Ljava/lang/String;FFI)I", at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateDrawnString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "draw(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateDrawnShadowString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "drawWithShadow(Ljava/lang/String;FFI)I", at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateShadowString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "getStringWidth(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translateMeasuredString(String text) {
        return RenderedTextBridge.translate(text);
    }
}
